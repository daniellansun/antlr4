#!/usr/bin/env sh

mvn clean test -Dantlr.testinprocess=true -DJDK_SOURCE_ROOT=../runtime/Java/src -Dperformance.package=
