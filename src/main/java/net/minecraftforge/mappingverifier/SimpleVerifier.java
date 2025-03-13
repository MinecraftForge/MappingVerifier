/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mappingverifier;

import java.util.ArrayList;
import java.util.List;

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
}
