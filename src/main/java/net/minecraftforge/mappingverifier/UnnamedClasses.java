/*
 * Mapping Verifier
 * Copyright (c) 2016-2020.
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

import java.util.List;
import java.util.stream.Collectors;

import net.minecraftforge.srgutils.IMappingFile.IClass;

public class UnnamedClasses extends SimpleVerifier {
    protected UnnamedClasses(MappingVerifier verifier) {
        super(verifier);
    }

    @Override
    public boolean process() {
        List<String> unnamed = verifier.getMappings()
        .getClasses().stream().map(IClass::getMapped)
        .filter(c -> c.contains("C_"))
        .collect(Collectors.toList());

        unnamed.forEach(c -> error("    Unnamed Class: " + c));
        return unnamed.isEmpty();
    }
}
