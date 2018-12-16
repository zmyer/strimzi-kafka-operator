/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.CustomResource;
import org.junit.rules.MethodRule;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.function.BiFunction;
import java.util.function.Function;

import static java.util.Collections.emptyMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

class ResourceTester<R extends HasMetadata, M extends AbstractModel> implements MethodRule {

    private final KafkaVersion.Lookup lookup;
    private Class<R> cls;
    private String prefix;
    private M model;
    private BiFunction<R, KafkaVersion.Lookup, M> fromK8sResource;
    private String resourceName;

    ResourceTester(Class<R> cls, KafkaVersion.Lookup lookup, BiFunction<R, KafkaVersion.Lookup, M> fromK8sResource) {
        this.lookup = lookup;
        this.cls = cls;
        this.fromK8sResource = fromK8sResource;
    }

    ResourceTester(Class<R> cls, Function<R, M> fromK8sResource) {
        this.lookup = new KafkaVersion.Lookup(emptyMap(), emptyMap(), emptyMap(), emptyMap());
        this.cls = cls;
        this.fromK8sResource = (x, y) -> fromK8sResource.apply(x);
    }

    static <T> T fromYaml(URL url, Class<T> c) {
        if (url == null) {
            return null;
        }
        ObjectMapper mapper = new YAMLMapper();
        try {
            return mapper.readValue(url, c);
        } catch (InvalidFormatException e) {
            throw new IllegalArgumentException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> String toYamlString(T instance) {
        ObjectMapper mapper = new YAMLMapper();
        try {
            return mapper.writeValueAsString(instance);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    protected void assertDesiredResource(String suffix, Function<M, ?> fn) throws IOException {
        assertNotNull("The resource " + resourceName + " does not exist", model);
        String content = readResource(prefix + suffix);
        if (content != null) {
            String ssStr = toYamlString(fn.apply(model));
            assertEquals(content.trim(), ssStr.trim());
        } else {
            fail("The resource " + prefix + suffix + " does not exist");
        }
    }

    private String readResource(String resource) throws IOException {
        InputStream expectedStream = getClass().getResourceAsStream(resource);
        if (expectedStream != null) {
            try {
                StringBuilder sb = new StringBuilder();
                BufferedReader reader = new BufferedReader(new InputStreamReader(expectedStream));
                String line = reader.readLine();
                while (line != null) {
                    sb.append(line).append("\n");
                    line = reader.readLine();
                }
                return sb.toString();
            } finally {
                expectedStream.close();
            }
        } else {
            return null;
        }
    }

    @Override
    public Statement apply(Statement base, FrameworkMethod method, Object target) {
        this.prefix = method.getMethod().getDeclaringClass().getSimpleName() + "." + method.getName();
        // Parse resource into CM
        try {
            resourceName = CustomResource.class.isAssignableFrom(cls) ?
                    prefix + "-" + cls.newInstance().getKind() + ".yaml" :
                    prefix + "-" + cls.getSimpleName() + ".yaml";
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        URL resource = getClass().getResource(resourceName);
        if (resource == null) {
            model = null;
        } else {
            R cm = fromYaml(resource, cls);
            // Construct the desired resources from the CM
            model = fromK8sResource.apply(cm, lookup);
        }
        return base;
    }
}
