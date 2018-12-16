#!/bin/sh
set -e

export PULL_REQUEST=${PULL_REQUEST:-true}
export BRANCH=${BRANCH:-master}
export TAG=${TAG:-latest}
export DOCKER_ORG=${DOCKER_ORG:-strimzici}
export DOCKER_REGISTRY=${DOCKER_REGISTRY:-docker.io}
export DOCKER_TAG=$COMMIT

make docu_check
make findbugs
make docker_build

if [ ! -e  documentation/book/appendix_crds.adoc ] ; then
  exit 1
fi

CHANGED_DERIVED=$(git diff --name-status -- install/ helm-charts/ documentation/book/appendix_crds.adoc cluster-operator/src/main/resources/cluster-roles)
if [ -n "$CHANGED_DERIVED" ] ; then
  echo "ERROR: Uncommitted changes in derived resources:"
  echo "$CHANGED_DERIVED"
  echo "Run the following to add up-to-date resources:"
  echo "  mvn clean verify -DskipTests -DskipITs \\"
  echo "    && git add install/ helm-charts/ documentation/book/appendix_crds.adoc cluster-operator/src/main/resources/cluster-roles"
  echo "    && git commit -m 'Update derived resources'"
  exit 1
fi

# Use local registry for system tests
OLD_DOCKER_REGISTRY=$DOCKER_REGISTRY
export DOCKER_REGISTRY="localhost:5000"
export DOCKER_TAG=$BRANCH
echo "Docker push with registry $DOCKER_REGISTRY and org $DOCKER_ORG under tag $DOCKER_TAG"
make docker_push

export DOCKER_TAG=$COMMIT
echo "Docker push with registry $DOCKER_REGISTRY and org $DOCKER_ORG under tag $DOCKER_TAG"
make docker_push

OLD_DOCKER_ORG=$DOCKER_ORG
export DOCKER_ORG="localhost:5000/strimzici"



echo "Running systemtests"
./systemtest/scripts/run_tests.sh ${SYSTEMTEST_ARGS}

# Revert modified DOCKER_REGISTRY and DOCKER_ORG after system tests
export DOCKER_REGISTRY=$OLD_DOCKER_REGISTRY
export DOCKER_ORG=$OLD_DOCKER_ORG

# If that worked we can push to the real docker org
if [ "$PULL_REQUEST" != "false" ] ; then
    make docu_html
    make docu_htmlnoheader
    echo "Building Pull Request - nothing to push"
elif [ "$TAG" = "latest" ] && [ "$BRANCH" != "master" ]; then
    make docu_html
    make docu_htmlnoheader
    echo "Not in master branch and not in release tag - nothing to push"
else
    echo "Login into Docker Hub ..."
    docker login -u $DOCKER_USER -p $DOCKER_PASS

    export DOCKER_ORG=strimzi
    export DOCKER_TAG=$TAG
    echo "Pushing to docker org $DOCKER_ORG"
    make docker_push
    if [ "$BRANCH" = "master" ]; then
        make docu_pushtowebsite
    fi
    make pushtonexus
fi
