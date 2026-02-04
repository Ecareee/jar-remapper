package com.ecaree.jarremapper.mapping;

import com.ecaree.jarremapper.JarRemapperExtension;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

public class MappingHelper {
    public static MappingData loadFromExtension(JarRemapperExtension extension) throws IOException {
        File mappingFile = extension.getEffectiveMappingFile();
        if (mappingFile == null || !mappingFile.exists()) {
            throw new IOException("Mapping file does not exist");
        }

        String sourceNs = extension.getSourceNamespace().getOrNull();
        String targetNs = extension.getTargetNamespace().getOrNull();

        MappingData mappingData = MappingLoader.load(mappingFile, sourceNs, targetNs);

        for (String pkg : extension.getExcludedPackages().getOrElse(Collections.emptyList())) {
            mappingData.addExcludedPackage(pkg);
        }

        return mappingData;
    }

    public static MappingData loadFromExtension(JarRemapperExtension extension, boolean reverse) throws IOException {
        File mappingFile = extension.getEffectiveMappingFile();
        if (mappingFile == null || !mappingFile.exists()) {
            throw new IOException("Mapping file does not exist");
        }

        String sourceNs = extension.getSourceNamespace().getOrNull();
        String targetNs = extension.getTargetNamespace().getOrNull();

        MappingData mappingData = MappingLoader.load(mappingFile, sourceNs, targetNs, reverse);

        for (String pkg : extension.getExcludedPackages().getOrElse(Collections.emptyList())) {
            mappingData.addExcludedPackage(pkg);
        }

        return mappingData;
    }
}