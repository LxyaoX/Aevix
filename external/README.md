# External Dependencies

This directory contains all the external dependencies required to build the Aevix framework.
They are included as git submodules to ensure version consistency and timely updating.

## Native dependencies

-   [Dobby](https://github.com/JingMatrix/Dobby): 
    A lightweight, multi-platform inline hooking framework. It serves as the backend for all native function hooking (`HookInline`).

-   [fmt](https://github.com/fmtlib/fmt):
    A modern formatting library used for high-performance, type-safe logging throughout the native code.

-   [LSPlant](https://github.com/JingMatrix/LSPlant):
    A hooking framework for the Android Runtime (ART). It provides the core functionality for intercepting and modifying Java methods.

-   [YukiPLT](https://github.com/Rouyashiki/YukiPLT):
    A library for PLT (Procedure Linkage Table) hooking. It is used in the `dex2oat` sub-project to rewrite the command line recorded in the OAT header. **Note:** Built as a static library and linked into `oat_hook` there; it is not compiled into the `external` C++ library itself.

-   [xz-embedded](https://github.com/tukaani-project/xz-embedded):
    A lightweight data compression library with a small footprint. It is used by the ELF parser to decompress the `.gnu_debugdata` section of stripped native libraries.

## Java libraries

-   [apache/commons-lang](https://github.com/apache/commons-lang):
    A package of Java utility classes for the classes that are in java.lang's hierarchy. Some classes are renamed and then used to implement the `XposedHelpers` API.

-   [axml/manifest-editor](https://github.com/JingMatrix/ManifestEditor):
    A a tool used to modify Android Manifest binary file. It is to parse manifestation files of Xposed modules.

