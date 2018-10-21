ConvertedSync
=============

This is a small tool written in Scala that synchronizes two separate folders (source -> target) while applying converters to the source files based on their mime type.
The main use-case is to convert high quality lossless audio from long term storage to lower fidelity formats for mobile devices with limited storage.
The tool itself does not implement the conversion, it simply calls external programs based on the detected file mime-types passing the source path and a target path.