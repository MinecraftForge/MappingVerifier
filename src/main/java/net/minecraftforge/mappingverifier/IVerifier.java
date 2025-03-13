/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mappingverifier;

import java.util.List;

public interface IVerifier {
    default String getName() {
        return this.getClass().getSimpleName();
    }

    public List<String> getErrors();

    /**
     * @return True if everything went fine, false if something was logged to errors.
     */
    boolean process();
}
