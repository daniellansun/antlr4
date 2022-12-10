@echo off
call mvn clean deploy -Dmaven.test.skip=true
