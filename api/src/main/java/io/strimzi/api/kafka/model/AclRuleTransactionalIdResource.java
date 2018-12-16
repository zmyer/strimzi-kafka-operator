/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.api.kafka.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import io.strimzi.crdgenerator.annotations.Description;
import io.sundr.builder.annotations.Buildable;
import io.vertx.core.cli.annotations.DefaultValue;

/**
 * A representation of a transactional ID resource for ACLs
 */
@Buildable(
        editableEnabled = false,
        generateBuilderPackage = false,
        builderPackage = "io.fabric8.kubernetes.api.builder"
)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({"type", "name", "patternType"})
public class AclRuleTransactionalIdResource extends AclRuleResource {
    private static final long serialVersionUID = 1L;

    public static final String TYPE_TRANSACTIONAL_ID = "transactionalId";

    private AclResourcePatternType patternType = AclResourcePatternType.LITERAL;

    private String name;

    @Description("Must be `" + TYPE_TRANSACTIONAL_ID + "`")
    @Override
    public String getType() {
        return TYPE_TRANSACTIONAL_ID;
    }

    @Description("Name of resource for which given ACL rule applies. " +
            "Can be combined with `patternType` field to use prefix pattern.")
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Description("Describes the pattern used in the resource field. " +
            "The supported types are `literal` and `prefix`. " +
            "With `literal` pattern type, the resource field will be used as a definition of a full name. " +
            "With `prefix` pattern type, the resource name will be used only as a prefix. " +
            "Default value is `literal`.")
    @DefaultValue("literal")
    @JsonInclude(value = JsonInclude.Include.NON_NULL)
    public AclResourcePatternType getPatternType() {
        return patternType;
    }

    public void setPatternType(AclResourcePatternType patternType) {
        this.patternType = patternType;
    }
}