/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mappingverifier;

import java.util.ArrayList;
import java.util.List;

import net.minecraftforge.srgutils.IMappingFile.IClass;

public abstract class SimpleVerifier implements IVerifier {
    protected final MappingVerifier verifier;
    private List<String> errors = new ArrayList<>();

    protected SimpleVerifier(MappingVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public List<String> getErrors() {
        return errors;
    }

    protected void error(String format, String... args) {
        String line = String.format(format, (Object[])args);
        Main.LOG.fine(line);
        errors.add(line);
    }

    protected String mapClass(String cls) {
        IClass mcls = verifier.getMappings().getClass(cls);
        return mcls == null ? cls : mcls.getMapped();
    }

    protected String mapField(String cls, String name) {
        return mapField(verifier.getMappings().getClass(cls), name);
    }

    protected String mapMethod(String cls, String name, String desc) {
        return mapMethod(verifier.getMappings().getClass(cls), name, desc);
    }

    protected String mapField(IClass cls, String name) {
        return cls == null ? name : cls.remapField(name);
    }

    protected String mapMethod(IClass cls, String name, String desc) {
        return cls == null ? name : cls.remapMethod(name, desc);
    }

    protected String mapDescriptor(String desc) {
        return verifier.getMappings().remapDescriptor(desc);
    }
}
