SUBDIRS=java-base kafka-base zookeeper kafka kafka-connect kafka-connect/s2i \
        stunnel-base zookeeper-stunnel kafka-stunnel entity-operator-stunnel \
        test-client kafka-mirror-maker
DOCKER_TARGETS=docker_tag docker_push

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

vars:
	$(eval sha512 = $($(@)_sha))
	$(eval kversion = $(subst kafka_,,$(@)))
	$(eval kversion = $(subst _,.,$(kversion)))
	$(eval imagetag = $(DOCKER_TAG)-kafka-$(kversion))

# Build multiple versions of kafka-base and its dependencies
$(BUILD_KAFKA_VERSIONS): vars
	# Build $(kversion) with SHA $(sha512)

	# Build kafka-base, tagging with $(imagetag)
	DOCKER_BUILD_ARGS="$(DOCKER_BUILD_ARGS) --build-arg KAFKA_VERSION=$(kversion) --build-arg KAFKA_SHA512=$(sha512)" \
        $(MAKE) -C kafka-base docker_build

	# Build kafka, tagging with $(kversion)
	DOCKER_BUILD_ARGS="$(DOCKER_BUILD_ARGS) --build-arg KAFKA_VERSION=$(kversion) --build-arg KAFKA_SHA512=$(sha512)" \
        $(MAKE) -C kafka docker_build

	# Build zookeeper, tagging with $(kversion)
	DOCKER_BUILD_ARGS="$(DOCKER_BUILD_ARGS) --build-arg KAFKA_VERSION=$(kversion) --build-arg KAFKA_SHA512=$(sha512)" \
        $(MAKE) -C zookeeper docker_build

	# Build kafka-connect, tagging with $(kversion)
	DOCKER_BUILD_ARGS="$(DOCKER_BUILD_ARGS) --build-arg KAFKA_VERSION=$(kversion) --build-arg KAFKA_SHA512=$(sha512)" \
        $(MAKE) -C kafka-connect docker_build

	# Build kafka-connect/s2i, tagging with $(kversion)
	DOCKER_BUILD_ARGS="$(DOCKER_BUILD_ARGS) --build-arg KAFKA_VERSION=$(kversion) --build-arg KAFKA_SHA512=$(sha512)" \
        $(MAKE) -C kafka-connect/s2i docker_build

	# Build kafka-mirror-maker, tagging with $(kversion)
	DOCKER_BUILD_ARGS="$(DOCKER_BUILD_ARGS) --build-arg KAFKA_VERSION=$(kversion) --build-arg KAFKA_SHA512=$(sha512)" \
        $(MAKE) -C kafka-mirror-maker docker_build

	# Build test-client, tagging with $(kversion)
	DOCKER_BUILD_ARGS="$(DOCKER_BUILD_ARGS) --build-arg KAFKA_VERSION=$(kversion) --build-arg KAFKA_SHA512=$(sha512)" \
        $(MAKE) -C test-client docker_build


# Build multiple versions of kafka-base and its dependencies
$(TAG_KAFKA_VERSIONS): vars
	# Build $(kversion) with SHA $(sha512)

	# Tagging kafka-base with $(imagetag)
	DOCKER_TAG=$(imagetag) \
        $(MAKE) -C kafka-base docker_tag

	# Tagging kafka with $(imagetag)
	DOCKER_TAG=$(imagetag) \
        $(MAKE) -C kafka docker_tag

	# Tagging zookeeper with $(kversion)
	DOCKER_TAG=$(imagetag) \
        $(MAKE) -C zookeeper docker_tag

	# Tagging kafka-connect with $(kversion)
	DOCKER_TAG=$(imagetag) \
        $(MAKE) -C kafka-connect docker_tag

	# Tagging kafka-connect/s2i with $(kversion)
	DOCKER_TAG=$(imagetag) \
        $(MAKE) -C kafka-connect/s2i docker_tag

	# Tagging kafka-mirror-maker with $(kversion)
	DOCKER_TAG=$(imagetag) \
        $(MAKE) -C kafka-mirror-maker docker_tag

	# Tagging test-client with $(kversion)
	DOCKER_TAG=$(imagetag) \
        $(MAKE) -C test-client docker_tag
