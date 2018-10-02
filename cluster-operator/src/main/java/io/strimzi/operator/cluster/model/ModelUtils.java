/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;


import io.fabric8.kubernetes.api.model.Secret;
import io.strimzi.api.kafka.model.CertificateAuthority;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

public class ModelUtils {
    private ModelUtils() {}

    public static final String DEFAULT_KAFKA_VERSION = "2.0.0";

    /**
     * Find the first secret in the given secrets with the given name
     */
    public static Secret findSecretWithName(List<Secret> secrets, String sname) {
        return secrets.stream().filter(s -> s.getMetadata().getName().equals(sname)).findFirst().orElse(null);
    }

    public static int getCertificateValidity(CertificateAuthority certificateAuthority) {
        int validity = AbstractModel.CERTS_EXPIRATION_DAYS;
        if (certificateAuthority != null
                && certificateAuthority.getValidityDays() > 0) {
            validity = certificateAuthority.getValidityDays();
        }
        return validity;
    }

    public static int getRenewalDays(CertificateAuthority certificateAuthority) {
        return certificateAuthority != null ? certificateAuthority.getRenewalDays() : 30;
    }

    /**
     * Parse an image map. It has the structure:
     *
     * imageMap ::= versionImage ( ',' versionImage )*
     * versionImage ::= version '=' image
     * version ::= [0-9.]+
     * image ::= [^,]+
     *
     * @param str
     * @return
     */
    public static Map<String, String> parseImageMap(String str) {
        if (str != null) {
            StringTokenizer tok = new StringTokenizer(str, ", \t\n\r");
            HashMap<String, String> map = new HashMap<>();
            while (tok.hasMoreTokens()) {
                String versionImage = tok.nextToken();
                int endIndex = versionImage.indexOf('=');
                String version = versionImage.substring(0, endIndex);
                String image = versionImage.substring(endIndex + 1);
                map.put(version.trim(), image.trim());
            }
            return Collections.unmodifiableMap(map);
        } else {
            return Collections.emptyMap();
        }
    }
}
