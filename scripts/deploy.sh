#!/usr/bin/env bash

./gradlew bintrayUpload
./gradlew publishRelease --stacktrace --info
