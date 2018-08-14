/*
 * Mapping Verifier
 * Copyright (c) 2016-2018.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */
package net.minecraftforge.mappingverifier;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DuplicateSrgIds extends SimpleVerifier
{
    private static final Pattern PATTERN = Pattern.compile("func_([0-9]*)_[a-z]*.*");

    @Override
    public boolean process(final InheratanceMap inheritance, final Mappings mappings)
    {
        final Map<Integer, String> idToName = new HashMap<>();
        final Map<String, String> globalMethods = new HashMap<>();
        return inheritance.getRead()
                .sorted(Comparator.comparing(o -> o.name))
                .map(cls -> {
                    Main.LOG.info("  Processing: " + mappings.map(cls.name));
                    final Mappings.ClsInfo classMappings = mappings.getClass(cls.name);
                    return cls.methods.values().stream()
                            .sequential()
                            .sorted((o1, o2) -> o1.name.equals(o2.name) ? o1.desc.compareTo(o2.desc) : o1.name.compareTo(o2.name))
                            .map(method -> {
                                final String newDesc = method.desc;
                                final String newName = classMappings.map(method.name, newDesc);
                                globalMethods.put(newName, method.desc);
                                final Matcher matcher = PATTERN.matcher(newName);
                                if(matcher.find()) {
                                    final int srg = Integer.valueOf(matcher.group(1));
                                    final String oldValue = idToName.putIfAbsent(srg, newName);
                                    if(oldValue != null && !oldValue.equals(newName)) {
                                        final String oldDesc = globalMethods.get(oldValue);
                                        this.error("SRG %s: %s %s  //  %s %s", String.valueOf(srg), oldValue, oldDesc, newName, newDesc);
                                        return false;
                                    }
                                }
                                return true;
                            })
                            .reduce(true, (a, b) -> a && b);
                }).reduce(true, (a, b) -> a && b);
    }
}
