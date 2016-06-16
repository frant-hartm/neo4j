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
package org.neo4j.coreedge.raft.roles;

import java.io.IOException;

import org.neo4j.coreedge.catchup.storecopy.LocalDatabase;
import org.neo4j.coreedge.raft.NewLeaderBarrier;
import org.neo4j.coreedge.raft.RaftMessageHandler;
import org.neo4j.coreedge.raft.RaftMessages;
import org.neo4j.coreedge.raft.outcome.Outcome;
import org.neo4j.coreedge.raft.state.RaftState;
import org.neo4j.coreedge.raft.state.ReadableRaftState;
import org.neo4j.kernel.impl.store.StoreId;
import org.neo4j.logging.Log;

import static org.neo4j.coreedge.raft.MajorityIncludingSelfQuorum.isQuorum;
import static org.neo4j.coreedge.raft.roles.Role.CANDIDATE;
import static org.neo4j.coreedge.raft.roles.Role.FOLLOWER;
import static org.neo4j.coreedge.raft.roles.Role.LEADER;

public class Candidate implements RaftMessageHandler
{
    @Override
    public  Outcome handle( RaftMessages.RaftMessage message, ReadableRaftState ctx,
                                            Log log, LocalDatabase localDatabase ) throws IOException
    {
        Outcome outcome = new Outcome( CANDIDATE, ctx );

        switch ( message.type() )
        {
            case HEARTBEAT:
            {
                RaftMessages.Heartbeat req = (RaftMessages.Heartbeat) message;

                if ( req.leaderTerm() < ctx.term() )
                {
                    break;
                }

                outcome.setNextRole( FOLLOWER );
                log.info( "Moving to FOLLOWER state after receiving heartbeat from %s at term %d (i am at %d)%n",
                        req.from(), req.leaderTerm(), ctx.term() );
                Heart.beat( ctx, outcome, (RaftMessages.Heartbeat) message );
                break;
            }

            case APPEND_ENTRIES_REQUEST:
            {
                RaftMessages.AppendEntries.Request req = (RaftMessages.AppendEntries.Request) message;

                if ( req.leaderTerm() < ctx.term() )
                {
                    RaftMessages.AppendEntries.Response appendResponse =
                            new RaftMessages.AppendEntries.Response( ctx.myself(), ctx.term(), false,
                                    req.prevLogIndex(), ctx.entryLog().appendIndex() );

                    outcome.addOutgoingMessage( new RaftMessages.Directed( req.from(), appendResponse ) );
                    break;
                }

                outcome.setNextRole( FOLLOWER );
                log.info( "Moving to FOLLOWER state after receiving append entries request from %s at term %d (i am at %d)%n",
                        req.from(), req.leaderTerm(), ctx.term() );
                Appending.handleAppendEntriesRequest( ctx, outcome, req, localDatabase.storeId() );
                break;
            }

            case VOTE_RESPONSE:
            {
                RaftMessages.Vote.Response res = (RaftMessages.Vote.Response) message;

                if ( res.term() > ctx.term() )
                {
                    outcome.setNextTerm( res.term() );
                    outcome.setNextRole( FOLLOWER );
                    log.info( "Moving to FOLLOWER state after receiving vote response from %s at term %d (i am at %d)%n",
                            res.from(), res.term(), ctx.term() );
                    break;
                }
                else if ( res.term() < ctx.term() || !res.voteGranted() )
                {
                    break;
                }

                if ( !res.from().equals( ctx.myself() ) )
                {
                    outcome.addVoteForMe( res.from() );
                }

                if ( isQuorum( ctx.votingMembers().size(), outcome.getVotesForMe().size() ) )
                {

                    outcome.setLeader( ctx.myself() );
                    Appending.appendNewEntry( ctx, outcome, new NewLeaderBarrier() );

                    outcome.setLastLogIndexBeforeWeBecameLeader( ctx.entryLog().appendIndex() );
                    outcome.electedLeader();
                    outcome.setNextRole( LEADER );
                    log.info( "Moving to LEADER state at term %d (i am %s), voted for by %s%n",
                            ctx.term(), ctx.myself(), outcome.getVotesForMe() );
                }
                break;
            }

            case VOTE_REQUEST:
            {
                RaftMessages.Vote.Request req = (RaftMessages.Vote.Request) message;
                if ( req.term() > ctx.term() )
                {
                    outcome.getVotesForMe().clear();
                    outcome.setNextRole( FOLLOWER );
                    log.info( "Moving to FOLLOWER state after receiving vote request from %s at term %d (i am at %d)%n",
                            req.from(), req.term(), ctx.term() );
                    Voting.handleVoteRequest( ctx, outcome, req, localDatabase.storeId() );
                    break;
                }

                outcome.addOutgoingMessage( new RaftMessages.Directed( req.from(),
                        new RaftMessages.Vote.Response( ctx.myself(), outcome.getTerm(), false ) ) );
                break;
            }

            case ELECTION_TIMEOUT:
            {
                if ( !Election.start( ctx, outcome, log ) )
                {
                    log.info( "Moving to FOLLOWER state after failing to start election" );
                    outcome.setNextRole( FOLLOWER );
                }
                break;
            }
        }

        return outcome;
    }

    @Override
    public Outcome validate( RaftMessages.RaftMessage message, StoreId storeId,
                              RaftState ctx, Log log, LocalDatabase localDatabase )
    {
        return new Outcome( CANDIDATE, ctx );
    }
}
