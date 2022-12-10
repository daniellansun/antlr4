@echo off
call mvn clean deploy -Dmaven.test.skip=true -Duser.language=en
