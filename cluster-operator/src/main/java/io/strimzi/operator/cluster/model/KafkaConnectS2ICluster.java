/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.ObjectReference;
import io.fabric8.openshift.api.model.BinaryBuildSource;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.openshift.api.model.BuildTriggerPolicy;
import io.fabric8.openshift.api.model.DeploymentConfig;
import io.fabric8.openshift.api.model.DeploymentConfigBuilder;
import io.fabric8.openshift.api.model.DeploymentStrategy;
import io.fabric8.openshift.api.model.DeploymentStrategyBuilder;
import io.fabric8.openshift.api.model.DeploymentTriggerPolicy;
import io.fabric8.openshift.api.model.DeploymentTriggerPolicyBuilder;
import io.fabric8.openshift.api.model.ImageChangeTrigger;
import io.fabric8.openshift.api.model.ImageLookupPolicyBuilder;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.api.model.ImageStreamBuilder;
import io.fabric8.openshift.api.model.TagImportPolicyBuilder;
import io.fabric8.openshift.api.model.TagReference;
import io.fabric8.openshift.api.model.TagReferencePolicyBuilder;
import io.strimzi.api.kafka.model.KafkaConnectS2I;
import io.strimzi.api.kafka.model.KafkaConnectS2ISpec;
import io.strimzi.operator.common.model.Labels;

import java.util.Map;

public class KafkaConnectS2ICluster extends KafkaConnectCluster {

    // Kafka Connect S2I configuration
    protected String sourceImageBaseName;
    protected String sourceImageTag;
    protected String tag = "latest";
    protected boolean insecureSourceRepository = false;

    /**
     * Constructor
     *
     * @param namespace Kubernetes/OpenShift namespace where Kafka Connect cluster resources are going to be created
     * @param cluster   overall cluster name
     */
    private KafkaConnectS2ICluster(String namespace, String cluster, Labels labels) {
        super(namespace, cluster, labels);
        this.validLoggerFields = getDefaultLogConfig();
    }

    public static KafkaConnectS2ICluster fromCrd(KafkaConnectS2I kafkaConnectS2I, KafkaVersion.Lookup versions) {
        KafkaConnectS2ISpec spec = kafkaConnectS2I.getSpec();
        KafkaConnectS2ICluster cluster = fromSpec(spec, versions, new KafkaConnectS2ICluster(kafkaConnectS2I.getMetadata().getNamespace(),
                kafkaConnectS2I.getMetadata().getName(),
                Labels.fromResource(kafkaConnectS2I).withKind(kafkaConnectS2I.getKind())));

        cluster.setOwnerReference(kafkaConnectS2I);
        cluster.setInsecureSourceRepository(spec != null ? spec.isInsecureSourceRepository() : false);

        return cluster;
    }

    /**
     * Generate new DeploymentConfig
     *
     * @return      Source ImageStream resource definition
     */
    public DeploymentConfig generateDeploymentConfig(Map<String, String> annotations, boolean isOpenShift) {
        Container container = new ContainerBuilder()
                .withName(name)
                .withImage(image)
                .withEnv(getEnvVars())
                .withPorts(getContainerPortList())
                .withLivenessProbe(createHttpProbe(livenessPath, REST_API_PORT_NAME, livenessInitialDelay, livenessTimeout))
                .withReadinessProbe(createHttpProbe(readinessPath, REST_API_PORT_NAME, readinessInitialDelay, readinessTimeout))
                .withVolumeMounts(getVolumeMounts())
                .withResources(ModelUtils.resources(getResources()))
                .build();

        DeploymentTriggerPolicy configChangeTrigger = new DeploymentTriggerPolicyBuilder()
                .withType("ConfigChange")
                .build();

        DeploymentTriggerPolicy imageChangeTrigger = new DeploymentTriggerPolicyBuilder()
                .withType("ImageChange")
                .withNewImageChangeParams()
                    .withAutomatic(true)
                    .withContainerNames(name)
                    .withNewFrom()
                        .withKind("ImageStreamTag")
                        .withName(image)
                    .endFrom()
                .endImageChangeParams()
                .build();

        DeploymentStrategy updateStrategy = new DeploymentStrategyBuilder()
                .withType("Rolling")
                .withNewRollingParams()
                    .withMaxSurge(new IntOrString(1))
                    .withMaxUnavailable(new IntOrString(0))
                .endRollingParams()
                .build();

        DeploymentConfig dc = new DeploymentConfigBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withLabels(getLabelsWithName(templateDeploymentLabels))
                    .withAnnotations(mergeAnnotations(null, templateDeploymentAnnotations))
                    .withNamespace(namespace)
                    .withOwnerReferences(createOwnerReference())
                .endMetadata()
                .withNewSpec()
                    .withReplicas(replicas)
                    .withSelector(getSelectorLabels())
                    .withNewTemplate()
                        .withNewMetadata()
                            .withAnnotations(mergeAnnotations(annotations, templatePodAnnotations))
                            .withLabels(getLabelsWithName(templatePodLabels))
                        .endMetadata()
                        .withNewSpec()
                            .withContainers(container)
                            .withVolumes(getVolumes(isOpenShift))
                            .withTolerations(getTolerations())
                            .withAffinity(getMergedAffinity())
                        .endSpec()
                    .endTemplate()
                    .withTriggers(configChangeTrigger, imageChangeTrigger)
                .withStrategy(updateStrategy)
                .endSpec()
                .build();
        return dc;
    }

    /**
     * Generate new source ImageStream
     *
     * @return      Source ImageStream resource definition
     */
    public ImageStream generateSourceImageStream() {
        ObjectReference image = new ObjectReference();
        image.setKind("DockerImage");
        image.setName(sourceImageBaseName + ":" + sourceImageTag);

        TagReference sourceTag = new TagReference();
        sourceTag.setName(sourceImageTag);
        sourceTag.setFrom(image);

        if (insecureSourceRepository)   {
            sourceTag.setImportPolicy(new TagImportPolicyBuilder().withInsecure(true).build());
            sourceTag.setReferencePolicy(new TagReferencePolicyBuilder().withType("Local").build());
        }

        ImageStream imageStream = new ImageStreamBuilder()
                .withNewMetadata()
                    .withName(getSourceImageStreamName())
                    .withNamespace(namespace)
                    .withLabels(getLabelsWithName(getSourceImageStreamName()))
                    .withOwnerReferences(createOwnerReference())
                .endMetadata()
                .withNewSpec()
                    .withLookupPolicy(new ImageLookupPolicyBuilder().withLocal(false).build())
                    .withTags(sourceTag)
                .endSpec()
                .build();

        return imageStream;
    }

    /**
     * Generate new target ImageStream
     *
     * @return      Target ImageStream resource definition
     */
    public ImageStream generateTargetImageStream() {
        ImageStream imageStream = new ImageStreamBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withNamespace(namespace)
                    .withLabels(getLabelsWithName())
                    .withOwnerReferences(createOwnerReference())
                .endMetadata()
                .withNewSpec()
                    .withLookupPolicy(new ImageLookupPolicyBuilder().withLocal(true).build())
                .endSpec()
                .build();

        return imageStream;
    }

    /**
     * Generate new BuildConfig
     *
     * @return      BuildConfig resource definition
     */
    public BuildConfig generateBuildConfig() {
        BuildTriggerPolicy triggerConfigChange = new BuildTriggerPolicy();
        triggerConfigChange.setType("ConfigChange");

        BuildTriggerPolicy triggerImageChange = new BuildTriggerPolicy();
        triggerImageChange.setType("ImageChange");
        triggerImageChange.setImageChange(new ImageChangeTrigger());

        BuildConfig build = new BuildConfigBuilder()
                .withNewMetadata()
                    .withName(name)
                    .withLabels(getLabelsWithName())
                    .withNamespace(namespace)
                    .withOwnerReferences(createOwnerReference())
                .endMetadata()
                .withNewSpec()
                    .withFailedBuildsHistoryLimit(5)
                    .withNewOutput()
                        .withNewTo()
                            .withKind("ImageStreamTag")
                            .withName(image)
                        .endTo()
                    .endOutput()
                    .withRunPolicy("Serial")
                    .withNewSource()
                        .withType("Binary")
                        .withBinary(new BinaryBuildSource())
                    .endSource()
                    .withNewStrategy()
                        .withType("Source")
                        .withNewSourceStrategy()
                            .withNewFrom()
                                .withKind("ImageStreamTag")
                                .withName(getSourceImageStreamName() + ":" + sourceImageTag)
                            .endFrom()
                        .endSourceStrategy()
                    .endStrategy()
                    .withTriggers(triggerConfigChange, triggerImageChange)
                .endSpec()
                .build();

        return build;
    }

    /**
     * Generates the name of the source ImageStream
     *
     * @return               Name of the source ImageStream instance
     */
    public String getSourceImageStreamName() {
        return getSourceImageStreamName(name);
    }

    /**
     * Generates the name of the source ImageStream
     *
     * @param baseName       Name of the Kafka Connect cluster
     * @return               Name of the source ImageStream instance
     */
    public static String getSourceImageStreamName(String baseName) {
        return baseName + "-source";
    }

    @Override
    protected void setImage(String image) {
        this.sourceImageBaseName = image.substring(0, image.lastIndexOf(":"));
        this.sourceImageTag = image.substring(image.lastIndexOf(":") + 1);
        this.image = name + ":" + tag;
    }

    /**
     * @return true if the source repo for the S2I image should be treated as insecure in source ImageStream
     */
    public boolean isInsecureSourceRepository() {
        return insecureSourceRepository;
    }

    /**
     * Set whether the source repository for the S2I image should be treated as insecure
     *
     * @param insecureSourceRepository  Set to true for using insecure repository
     */
    public void setInsecureSourceRepository(boolean insecureSourceRepository) {
        this.insecureSourceRepository = insecureSourceRepository;
    }
}
