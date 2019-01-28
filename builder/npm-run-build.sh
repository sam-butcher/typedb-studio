#!/usr/bin/env bash

export PATH=`pwd`/external/nodejs/bin:$PATH
export NODE_PATH=`pwd`/node_modules/

npm run build

cp build/*.zip $1
