//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quickstart;

import java.io.File;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class PreconfigureJNDIWar
{
    private static final long __start = System.nanoTime();
    private static final Logger LOG = Log.getLogger(Server.class);

    public static void main(String[] args) throws Exception
    {
        String target = "target/test-jndi-preconfigured";
        File file = new File(target);
        if (file.exists())
            IO.delete(file);

        PreconfigureQuickStartWar.main("target/test-jndi.war", target, "src/test/resources/test-jndi.xml");

        LOG.info("Preconfigured in {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - __start));

        // IO.copy(new FileInputStream("target/test-jndi-preconfigured/WEB-INF/quickstart-web.xml"),System.out);
    }
}
