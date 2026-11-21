#!/usr/bin/env sh

mvn clean deploy -Dmaven.test.skip=true -Duser.language=en -Dmaven.build.cache.enabled=false
