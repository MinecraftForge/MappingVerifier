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

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

public class Main
{
    public static final String SIMPLE_NAME = Main.class.getSimpleName();
    public static final Logger LOG = Logger.getLogger(SIMPLE_NAME);
    public static final String VERSION = SIMPLE_NAME + " v" + Optional.ofNullable(Main.class.getPackage().getImplementationVersion()).orElse("Unknown") + " by LexManos";

    public static void main(String[] args) throws Exception
    {
        OptionParser parser = new OptionParser();
        parser.accepts("help").forHelp();
        parser.accepts("version").forHelp();
        OptionSpec<File> jarArg = parser.accepts("jar").withRequiredArg().ofType(File.class).required();
        OptionSpec<File> mapArg = parser.accepts("map").withRequiredArg().ofType(File.class).required();
        OptionSpec<String> logArg = parser.accepts("log").withRequiredArg().ofType(String.class);
        parser.accepts("verbose");

        try
        {
            OptionSet options = parser.parse(args);
            if (options.has("help"))
            {
                System.out.println(VERSION);
                parser.printHelpOn(System.out);
                return;
            }
            else if (options.has("version"))
            {
                System.out.println(VERSION);
                return;
            }

            File jarFile = jarArg.value(options);
            File mapFile = mapArg.value(options);
            String logFile = logArg.value(options);
            //String snapVersion = options.has(snapArg) ? snapArg.value(options) : null;
            boolean verbose = options.has("verbose");

            Main.LOG.setUseParentHandlers(false);
            Main.LOG.setLevel(Level.ALL);

            if (logFile != null)
            {
                FileHandler filehandler = new FileHandler(logFile);
                filehandler.setFormatter(new Formatter()
                {
                    @Override
                    public synchronized String format(LogRecord record)
                    {
                        StringBuffer sb = new StringBuffer();
                        String message = this.formatMessage(record);
                        sb.append(record.getLevel().getName()).append(": ").append(message).append("\n");
                        if (record.getThrown() != null)
                        {
                            try
                            {
                                StringWriter sw = new StringWriter();
                                PrintWriter pw = new PrintWriter(sw);
                                record.getThrown().printStackTrace(pw);
                                pw.close();
                                sb.append(sw.toString());
                            } catch (Exception ex){}
                        }
                        return sb.toString();
                    }

                });
                Main.LOG.addHandler(filehandler);
            }
            Main.LOG.addHandler(new Handler()
            {
                @Override
                public void publish(LogRecord record)
                {
                    if (verbose || record.getLevel().intValue() >= Level.WARNING.intValue())
                        System.out.println(String.format(record.getMessage(), record.getParameters()));
                }
                @Override public void flush() {}
                @Override public void close() throws SecurityException {}
            });

            log(Main.VERSION);
            log("Jar:      " + jarFile);
            log("Map:      " + mapFile);
            log("Log:      " + logFile);

            try
            {
                MappingVerifier mv = new MappingVerifier();

                mv.addDefaultTasks();
                mv.loadMap(mapFile);

                mv.loadJar(jarFile); //TODO: Add full classpath so we can check all classes including JVM?

                if (!mv.verify())
                {
                    for (IVerifier task : mv.getTasks())
                    {
                        if (!task.getErrors().isEmpty())
                        {
                            log("Task: " + task.getName());
                            task.getErrors().forEach(l -> log("    " + l));
                        }
                    }
                }
            }
            catch (Exception e)
            {
                System.err.println("ERROR: " + e.getMessage());
                Main.LOG.log(Level.SEVERE, "ERROR", e);
                e.printStackTrace();
                System.exit(1);
            }
        }
        catch (OptionException e)
        {
            parser.printHelpOn(System.out);
            e.printStackTrace();
        }
    }

    private static void log(String line)
    {
        LOG.warning(line);
    }
}
