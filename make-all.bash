#!/bin/bash -e

# Build the syncthing library

./make-go-nocgo.bash arm
./make-syncthing-nocgo.bash arm

./make-go-nocgo.bash 386
./make-syncthing-nocgo.bash 386

