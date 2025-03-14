/*
 * Copyright (c) Forge Development LLC
 * SPDX-License-Identifier: LGPL-2.1-only
 */
package net.minecraftforge.mappingverifier;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

@SuppressWarnings("serial")
public class UniqueDeque<E> extends ArrayDeque<E> {
    private final Set<Object> visited = new HashSet<>();
    private final Function<E, Object> mapper;

    public UniqueDeque() {
        this.mapper = v -> v;
    }

    public UniqueDeque(Function<E, Object> mapper) {
        this.mapper = mapper;
    }

    public UniqueDeque(Collection<? extends E> c) {
        this.mapper = v -> v;
        addAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        boolean ret = false;
        for (E e : c)
            ret |= add(e);
        return ret;
    }

    @Override
    public boolean add(E e) {
        if (e != null && visited.add(mapper.apply(e)))
            super.add(e);
        return false;
    }

    @Override
    public boolean offer(E e) {
        if (e != null && !visited.contains(mapper.apply(e)) && super.offer(e)) {
            visited.add(mapper.apply(e));
            return true;
        }
        return false;
    }
}
