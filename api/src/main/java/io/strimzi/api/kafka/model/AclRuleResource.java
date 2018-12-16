/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.strimzi.crdgenerator.annotations.Description;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

/**
 * A representation of a single ACL rule for SimpleAclAuthorizer
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(name = AclRuleTopicResource.TYPE_TOPIC, value = AclRuleTopicResource.class),
        @JsonSubTypes.Type(name = AclRuleGroupResource.TYPE_GROUP, value = AclRuleGroupResource.class),
        @JsonSubTypes.Type(name = AclRuleClusterResource.TYPE_CLUSTER, value = AclRuleClusterResource.class),
        @JsonSubTypes.Type(name = AclRuleTransactionalIdResource.TYPE_TRANSACTIONAL_ID, value = AclRuleTransactionalIdResource.class),
})
@JsonInclude(JsonInclude.Include.NON_NULL)
public abstract class AclRuleResource implements Serializable {
    private static final long serialVersionUID = 1L;

    private Map<String, Object> additionalProperties = new HashMap<>(0);

    @Description("Resource type. " +
            "The available resource types are `topic`, `group`, `cluster`, and `transactionalId`.")
    public abstract String getType();

    @JsonAnyGetter
    public Map<String, Object> getAdditionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(String name, Object value) {
        this.additionalProperties.put(name, value);
    }
}