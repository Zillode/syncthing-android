#!/bin/bash -e

# Build the syncthing library

./make-go-nocgo.bash arm
./make-syncthing-nocgo.bash arm

./make-go-nocgo.bash 386
./make-syncthing-nocgo.bash 386

./make-go-nocgo.bash amd64
./make-syncthing-nocgo.bash amd64

