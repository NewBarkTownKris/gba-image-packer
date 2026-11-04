#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
arm-none-eabi-gcc -mcpu=arm7tdmi -marm -Os -ffreestanding -fno-builtin -nostdlib -Wl,-T,firmware/link.ld -o firmware/viewer.elf firmware/start.s firmware/viewer.c -lgcc
arm-none-eabi-objcopy -O binary firmware/viewer.elf assets/viewer.bin
