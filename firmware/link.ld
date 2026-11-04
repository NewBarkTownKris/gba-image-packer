ENTRY(_start)
SECTIONS {
 . = 0x08000000;
 .text : { *(.text.start) *(.text*) *(.rodata*) }
 . = ALIGN(4);
 __album = .;
 /DISCARD/ : { *(.comment*) *(.ARM.attributes*) *(.ARM.exidx*) }
}
