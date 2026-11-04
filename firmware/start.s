.syntax unified
.cpu arm7tdmi
.arm
.section .text.start
.global _start
_start:
 b boot
 .space 188
boot:
 mov r0, #0x12
 msr cpsr_c, r0
 ldr sp, =0x03007fa0
 mov r0, #0x1f
 msr cpsr_c, r0
 ldr sp, =0x03007f00
 bl main
1: b 1b
