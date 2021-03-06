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

package org.eclipse.jetty.server.session;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * NullSessionCacheFactory
 *
 * Factory for NullSessionCaches.
 */
public class NullSessionCacheFactory extends AbstractSessionCacheFactory
{
    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    @Override
    public int getEvictionPolicy()
    {
        return SessionCache.EVICT_ON_SESSION_EXIT; //never actually stored
    }

    @Override
    public void setEvictionPolicy(int evictionPolicy)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Ignoring eviction policy setting for NullSessionCaches");
    }

    @Override
    public boolean isSaveOnInactiveEvict()
    {
        return false; //never kept in cache
    }

    @Override
    public void setSaveOnInactiveEvict(boolean saveOnInactiveEvict)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Ignoring eviction policy setting for NullSessionCaches");
    }

    @Override
    public SessionCache getSessionCache(SessionHandler handler)
    {
        NullSessionCache cache = new NullSessionCache(handler);
        cache.setSaveOnCreate(isSaveOnCreate());
        cache.setRemoveUnloadableSessions(isRemoveUnloadableSessions());
        cache.setFlushOnResponseCommit(isFlushOnResponseCommit());
        return cache;
    }
}
