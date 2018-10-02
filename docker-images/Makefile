SUBDIRS=java-base kafka-base zookeeper kafka kafka-connect kafka-connect/s2i \
        stunnel-base zookeeper-stunnel kafka-stunnel entity-operator-stunnel \
        test-client kafka-mirror-maker
DOCKER_TARGETS=docker_tag docker_push
DOCKER_TAG ?= latest

all: $(SUBDIRS)
clean: $(SUBDIRS)
$(DOCKER_TARGETS): $(SUBDIRS)

#$(SUBDIRS):
#	$(MAKE) -C $@ $(MAKECMDGOALS)

.PHONY: build clean release all $(SUBDIRS) $(DOCKER_TARGETS)

kafka_1_0_2_sha=4CBCDAF8CCC4EFE3D1B6275F3F2C32CF8F2F1A62104B5DD0BD9E2974160AB89D85A6E1791AF8B948A413B99ED696B06EA9D4299B27EA63C3F7318DABF5761144
kafka_1_1_1_sha=2A1EB9A7C8C8337C424EEFED7BAAE26B3DACBA6A4AB8B64D9A7D5C6EE2CDB66CFA76C5B366F23435941569B89BF02482625189016296B2EA2A05FD0F38F6B709
kafka_2_0_0_sha=b28e81705e30528f1abb6766e22dfe9dae50b1e1e93330c880928ff7a08e6b38ee71cbfc96ec14369b2dfd24293938702cab422173c8e01955a9d1746ae43f98

KAFKA_VERSIONS = kafka_1_0_2 kafka_1_1_1 kafka_2_0_0

BUILD_KAFKA_VERSIONS = $(subst kafka_,build_kafka_,$(KAFKA_VERSIONS))
TAG_KAFKA_VERSIONS = $(subst kafka_,tag_kafka_,$(KAFKA_VERSIONS))

docker_build: java-base stunnel-base zookeeper-stunnel kafka-stunnel entity-operator-stunnel \
    $(BUILD_KAFKA_VERSIONS)

docker_tag: java-base stunnel-base zookeeper-stunnel kafka-stunnel entity-operator-stunnel \
    $(TAG_KAFKA_VERSIONS)

# We don't need multiple versions of these, so build them normally
java-base stunnel-base zookeeper-stunnel kafka-stunnel entity-operator-stunnel:
	$(MAKE) -C $@ $(MAKECMDGOALS)

# Build multiple versions of kafka-base and its dependencies
$(BUILD_KAFKA_VERSIONS):
	# build whole dependency graph, tagging with 'latest' (so the FROM lines work)

	$(eval kversion = $(subst build_kafka_,,$(@)))
	$(eval sha512 = $(kafka_$(kversion)_sha))
	$(eval kversion = $(subst _,.,$(kversion)))
	$(eval imagetag = latest-kafka-$(kversion))
	# Build $(kversion) with SHA $(sha512)
	$(eval BUILD_ENV=DOCKER_BUILD_ARGS="--build-arg KAFKA_VERSION=$(kversion) --build-arg KAFKA_SHA512=$(sha512) $(DOCKER_BUILD_ARGS)")
	$(BUILD_ENV) $(MAKE) -C kafka-base docker_build
	$(BUILD_ENV) $(MAKE) -C kafka docker_build
	$(BUILD_ENV) $(MAKE) -C zookeeper docker_build
	$(BUILD_ENV) $(MAKE) -C kafka-connect docker_build
	$(BUILD_ENV) $(MAKE) -C kafka-connect/s2i docker_build
	$(BUILD_ENV) $(MAKE) -C kafka-mirror-maker docker_build
	$(BUILD_ENV) $(MAKE) -C test-client docker_build

	# Tag with versioned tag $(imagetag)
	$(eval TAG_ENV=DOCKER_TAG="$(imagetag)")
	$(TAG_ENV) $(MAKE) -C kafka-base docker_tag
	$(TAG_ENV) $(MAKE) -C kafka docker_tag
	$(TAG_ENV) $(MAKE) -C zookeeper docker_tag
	$(TAG_ENV) $(MAKE) -C kafka-connect docker_tag
	$(TAG_ENV) $(MAKE) -C kafka-connect/s2i docker_tag
	$(TAG_ENV) $(MAKE) -C kafka-mirror-maker docker_tag
	$(TAG_ENV) $(MAKE) -C test-client docker_tag


# Tag multiple versions of kafka-base and its dependencies
$(TAG_KAFKA_VERSIONS):
	$(eval sha512 = $(tag_$(@)_sha))
	$(eval kversion = $(subst tag_kafka_,,$(@)))
	$(eval kversion = $(subst _,.,$(kversion)))
	$(eval buildtag = latest-kafka-$(kversion))
	$(eval imagetag = $(DOCKER_TAG)-kafka-$(kversion))
	# Build $(kversion) with SHA $(sha512)
	$(eval TAG_ENV=BUILD_TAG="$(buildtag)" DOCKER_TAG="$(imagetag)")
	$(TAG_ENV) $(MAKE) -C kafka-base docker_tag
	$(TAG_ENV) $(MAKE) -C kafka docker_tag
	$(TAG_ENV) $(MAKE) -C zookeeper docker_tag
	$(TAG_ENV) $(MAKE) -C kafka-connect docker_tag
	$(TAG_ENV) $(MAKE) -C kafka-connect/s2i docker_tag
	$(TAG_ENV) $(MAKE) -C kafka-mirror-maker docker_tag
	$(TAG_ENV) $(MAKE) -C test-client docker_tag
