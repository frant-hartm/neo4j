/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.coreedge.raft.elections;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.neo4j.coreedge.raft.RaftInstance;
import org.neo4j.coreedge.server.CoreMember;
import org.neo4j.helpers.collection.Iterables;
import org.neo4j.kernel.impl.util.Listener;

public class ElectionUtil
{
    public static CoreMember waitForLeaderAgreement( Iterable<RaftInstance> validRafts, long maxTimeMillis ) throws
            InterruptedException, TimeoutException
    {
        long viewCount = Iterables.count( validRafts );

        Map<CoreMember, CoreMember> leaderViews = new HashMap<>();
        CompletableFuture<CoreMember> futureAgreedLeader = new CompletableFuture<>();

        Collection<Runnable> destructors = new ArrayList<>();
        for ( RaftInstance raft : validRafts )
        {
            destructors.add( leaderViewUpdatingListener( raft, validRafts, leaderViews, viewCount, futureAgreedLeader ) );
        }

        try
        {
            try
            {
                return futureAgreedLeader.get( maxTimeMillis, TimeUnit.MILLISECONDS );
            }
            catch ( ExecutionException e )
            {
                throw new RuntimeException( e );
            }
        }
        finally
        {
            destructors.forEach( Runnable::run );
        }
    }

    private static Runnable leaderViewUpdatingListener( RaftInstance raft, Iterable<RaftInstance>
            validRafts, Map<CoreMember,CoreMember> leaderViews, long viewCount, CompletableFuture<CoreMember>
            futureAgreedLeader )
    {
        Listener<CoreMember> listener = newLeader -> {
            synchronized ( leaderViews )
            {
                leaderViews.put( raft.identity(), newLeader );

                boolean leaderIsValid = false;
                for ( RaftInstance validRaft : validRafts )
                {
                    if ( validRaft.identity().equals( newLeader ) )
                    {
                        leaderIsValid = true;
                    }
                }

                if( newLeader != null && leaderIsValid && allAgreeOnLeader( leaderViews, viewCount, newLeader ) )
                {
                    futureAgreedLeader.complete( newLeader );
                }
            }
        };

        raft.registerListener( listener );
        return () -> raft.unregisterListener( listener );
    }

    private static <T> boolean allAgreeOnLeader( Map<T,T> leaderViews, long viewCount, T leader )
    {
        if ( leaderViews.size() != viewCount )
        {
            return false;
        }

        for ( T leaderView : leaderViews.values() )
        {
            if( !leader.equals( leaderView) )
            {
                return false;
            }
        }

        return true;
    }
}
