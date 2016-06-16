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
package org.neo4j.coreedge.raft.membership;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import org.neo4j.coreedge.network.Message;
import org.neo4j.coreedge.raft.DirectNetworking;
import org.neo4j.coreedge.raft.RaftTestFixture;
import org.neo4j.coreedge.raft.net.Inbound;
import org.neo4j.coreedge.raft.net.Outbound;
import org.neo4j.coreedge.server.CoreMember;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.neo4j.coreedge.raft.RaftInstance.Timeouts.ELECTION;
import static org.neo4j.coreedge.raft.RaftInstance.Timeouts.HEARTBEAT;
import static org.neo4j.coreedge.raft.roles.Role.FOLLOWER;
import static org.neo4j.coreedge.raft.roles.Role.LEADER;
import static org.neo4j.coreedge.server.RaftTestMember.member;

@RunWith(MockitoJUnitRunner.class)
public class RaftGroupMembershipTest
{
    @Mock
    private Outbound<CoreMember, Message> outbound;

    @Mock
    private Inbound inbound;

    @Test
    public void shouldNotFormGroupWithoutAnyBootstrapping() throws Exception
    {
        // given
        DirectNetworking net = new DirectNetworking();

        final CoreMember[] ids = {member( 0 ), member( 1 ), member( 2 )};

        RaftTestFixture fixture = new RaftTestFixture( net, 3, ids );

        fixture.members().setTargetMembershipSet( new RaftTestGroup( ids ).getMembers() );
        fixture.members().invokeTimeout( ELECTION );

        // when
        net.processMessages();

        // then
        assertThat( fixture.members(), hasCurrentMembers( new RaftTestGroup( new int[0] ) ) );
        assertEquals( 0, fixture.members().withRole( LEADER ).size() );
        assertEquals( 3, fixture.members().withRole( FOLLOWER ).size() );
    }

    @Test
    public void shouldAddSingleInstanceToExistingRaftGroup() throws Exception
    {
        // given
        DirectNetworking net = new DirectNetworking();

        final CoreMember leader = member( 0 );
        final CoreMember stable1 = member( 1 );
        final CoreMember stable2 = member( 2 );
        final CoreMember toBeAdded = member( 3 );

        final CoreMember[] initialMembers = {leader, stable1, stable2};
        final CoreMember[] finalMembers = {leader, stable1, stable2, toBeAdded};

        RaftTestFixture fixture = new RaftTestFixture( net, 3, finalMembers );

        fixture.members().withId( leader ).raftInstance().bootstrapWithInitialMembers( new RaftTestGroup(
                initialMembers ) );

        fixture.members().withId( leader ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        // when
        fixture.members().withId( leader ).raftInstance()
                .setTargetMembershipSet( new RaftTestGroup( finalMembers ).getMembers() );
        net.processMessages();

        fixture.members().withId( leader ).timeoutService().invokeTimeout( HEARTBEAT );
        net.processMessages();

        // then
        assertThat( fixture.members().withIds( finalMembers ), hasCurrentMembers( new RaftTestGroup( finalMembers ) ) );
        assertEquals( 1, fixture.members().withRole( LEADER ).size() );
        assertEquals( 3, fixture.members().withRole( FOLLOWER ).size() );
    }

    @Test
    public void shouldAddMultipleInstancesToExistingRaftGroup() throws Exception
    {
        // given
        DirectNetworking net = new DirectNetworking();

        final CoreMember leader = member( 0 );
        final CoreMember stable1 = member( 1 );
        final CoreMember stable2 = member( 2 );
        final CoreMember toBeAdded1 = member( 3 );
        final CoreMember toBeAdded2 = member( 4 );
        final CoreMember toBeAdded3 = member( 5 );

        final CoreMember[] initialMembers = {leader, stable1, stable2};
        final CoreMember[] finalMembers = {leader, stable1, stable2, toBeAdded1, toBeAdded2, toBeAdded3};

        RaftTestFixture fixture = new RaftTestFixture( net, 3, finalMembers );

        fixture.members().withId( leader ).raftInstance().bootstrapWithInitialMembers( new RaftTestGroup( initialMembers ) );
        fixture.members().withId( leader ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        // when
        fixture.members().setTargetMembershipSet( new RaftTestGroup( finalMembers ).getMembers() );
        net.processMessages();

        // We need a heartbeat for every member we add. It is necessary to have the new members report their state
        // so their membership change can be processed. We can probably do better here.
        fixture.members().withId( leader ).timeoutService().invokeTimeout( HEARTBEAT );
        net.processMessages();
        fixture.members().withId( leader ).timeoutService().invokeTimeout( HEARTBEAT );
        net.processMessages();
        fixture.members().withId( leader ).timeoutService().invokeTimeout( HEARTBEAT );
        net.processMessages();

        // then
        assertThat( fixture.members().withIds( finalMembers ), hasCurrentMembers( new RaftTestGroup( finalMembers ) ) );
        assertEquals( 1, fixture.members().withRole( LEADER ).size() );
        assertEquals( 5, fixture.members().withRole( FOLLOWER ).size() );
    }

    @Test
    public void shouldRemoveSingleInstanceFromExistingRaftGroup() throws Exception
    {
        DirectNetworking net = new DirectNetworking();

        // given
        final CoreMember leader = member( 0 );
        final CoreMember stable = member( 1 );
        final CoreMember toBeRemoved = member( 2 );

        final CoreMember[] initialMembers = {leader, stable, toBeRemoved};
        final CoreMember[] finalMembers = {leader, stable};

        RaftTestFixture fixture = new RaftTestFixture( net, 2, initialMembers );

        fixture.members().withId( leader ).raftInstance().bootstrapWithInitialMembers( new RaftTestGroup( initialMembers ) );
        fixture.members().withId( leader ).timeoutService().invokeTimeout( ELECTION );

        // when
        fixture.members().setTargetMembershipSet( new RaftTestGroup( finalMembers ).getMembers() );
        net.processMessages();

        // then
        assertThat( fixture.members().withIds( finalMembers ), hasCurrentMembers( new RaftTestGroup( finalMembers ) ) );
        assertEquals( 1, fixture.members().withIds( finalMembers ).withRole( LEADER ).size() );
        assertEquals( 1, fixture.members().withIds( finalMembers ).withRole( FOLLOWER ).size() );
    }

    @Test
    public void shouldRemoveMultipleInstancesFromExistingRaftGroup() throws Exception
    {
        DirectNetworking net = new DirectNetworking();

        // given
        final CoreMember leader = member( 0 );
        final CoreMember stable = member( 1 );
        final CoreMember toBeRemoved1 = member( 2 );
        final CoreMember toBeRemoved2 = member( 3 );
        final CoreMember toBeRemoved3 = member( 4 );

        final CoreMember[] initialMembers = {leader, stable, toBeRemoved1, toBeRemoved2, toBeRemoved3};
        final CoreMember[] finalMembers = {leader, stable};

        RaftTestFixture fixture = new RaftTestFixture( net, 2, initialMembers );

        fixture.members().withId( leader ).raftInstance().bootstrapWithInitialMembers( new RaftTestGroup( initialMembers ) );
        fixture.members().withId( leader ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        // when
        fixture.members().withId( leader ).raftInstance().setTargetMembershipSet( new RaftTestGroup( finalMembers ).getMembers() );
        net.processMessages();

        // then
        assertThat( fixture.members().withIds( finalMembers ), hasCurrentMembers( new RaftTestGroup( finalMembers ) ) );
        assertEquals( 1, fixture.members().withIds( finalMembers ).withRole( LEADER ).size() );
        assertEquals( 1, fixture.members().withIds( finalMembers ).withRole( FOLLOWER ).size() );
    }

    @Test
    public void shouldHandleMixedChangeToExistingRaftGroup() throws Exception
    {
        DirectNetworking net = new DirectNetworking();

        // given
        final CoreMember leader = member( 0 );
        final CoreMember stable = member( 1 );
        final CoreMember toBeRemoved1 = member( 2 );
        final CoreMember toBeRemoved2 = member( 3 );
        final CoreMember toBeAdded1 = member( 4 );
        final CoreMember toBeAdded2 = member( 5 );

        final CoreMember[] everyone = {leader, stable, toBeRemoved1, toBeRemoved2, toBeAdded1, toBeAdded2};

        final CoreMember[] initialMembers = {leader, stable, toBeRemoved1, toBeRemoved2};
        final CoreMember[] finalMembers = {leader, stable, toBeAdded1, toBeAdded2};

        RaftTestFixture fixture = new RaftTestFixture( net, 3, everyone );

        fixture.members().withId( leader ).raftInstance().bootstrapWithInitialMembers( new RaftTestGroup( initialMembers ) );
        fixture.members().withId( leader ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        // when
        fixture.members().withId( leader ).raftInstance().setTargetMembershipSet( new RaftTestGroup( finalMembers )
                .getMembers() );
        net.processMessages();

        fixture.members().withId( leader ).timeoutService().invokeTimeout( HEARTBEAT );
        net.processMessages();
        fixture.members().withId( leader ).timeoutService().invokeTimeout( HEARTBEAT );
        net.processMessages();
        fixture.members().withId( leader ).timeoutService().invokeTimeout( HEARTBEAT );
        net.processMessages();

        // then
        assertThat( fixture.members().withIds( finalMembers ), hasCurrentMembers( new RaftTestGroup( finalMembers ) ) );
        assertEquals( 1, fixture.members().withIds( finalMembers ).withRole( LEADER ).size() );
        assertEquals( 3, fixture.members().withIds( finalMembers ).withRole( FOLLOWER ).size() );
    }

    @Test
    public void shouldRemoveLeaderFromExistingRaftGroupAndActivelyTransferLeadership() throws Exception
    {
        DirectNetworking net = new DirectNetworking();

        // given
        final CoreMember leader = member( 0 );
        final CoreMember stable1 = member( 1 );
        final CoreMember stable2 = member( 2 );

        final CoreMember[] initialMembers = {leader, stable1, stable2};
        final CoreMember[] finalMembers = {stable1, stable2};

        RaftTestFixture fixture = new RaftTestFixture( net, 2, initialMembers );

        fixture.members().withId( leader ).raftInstance().bootstrapWithInitialMembers( new RaftTestGroup( initialMembers ) );
        fixture.members().withId( leader ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        // when
        fixture.members().withId( leader ).raftInstance().setTargetMembershipSet( new RaftTestGroup( finalMembers ).getMembers() );
        net.processMessages();

        fixture.members().withId( stable1 ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        // then
        assertThat( fixture.members().withIds( finalMembers ), hasCurrentMembers( new RaftTestGroup( finalMembers ) ) );
        assertTrue( fixture.members().withId( stable1 ).raftInstance().isLeader() ||
                fixture.members().withId( stable2 ).raftInstance().isLeader() );
    }

    @Test
    public void shouldRemoveLeaderAndAddItBackIn() throws Exception
    {
        DirectNetworking net = new DirectNetworking();

        // given
        final CoreMember leader1 = member( 0 );
        final CoreMember leader2 = member( 1 );
        final CoreMember stable1 = member( 2 );
        final CoreMember stable2 = member( 3 );

        final CoreMember[] allMembers = {leader1, leader2, stable1, stable2};
        final CoreMember[] fewerMembers = {leader2, stable1, stable2};

        RaftTestFixture fixture = new RaftTestFixture( net, 3, allMembers );

        // when
        fixture.members().withId( leader1 ).raftInstance().bootstrapWithInitialMembers( new RaftTestGroup( allMembers ) );
        fixture.members().withId( leader1 ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        fixture.members().withId( leader1 ).raftInstance().setTargetMembershipSet( new RaftTestGroup( fewerMembers )
                .getMembers() );
        net.processMessages();

        fixture.members().withId( leader2 ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        fixture.members().withId( leader2 ).raftInstance().setTargetMembershipSet( new RaftTestGroup( allMembers )
                .getMembers() );
        net.processMessages();

        fixture.members().withId( leader2 ).timeoutService().invokeTimeout( HEARTBEAT );
        net.processMessages();

        // then
        assertTrue( fixture.members().withId( leader2 ).raftInstance().isLeader() );
        assertThat( fixture.members().withIds( allMembers ), hasCurrentMembers( new RaftTestGroup( allMembers ) ) );
    }

    @Test
    public void shouldRemoveFollowerAndAddItBackIn() throws Exception
    {
        DirectNetworking net = new DirectNetworking();

        // given
        final CoreMember leader = member( 0 );
        final CoreMember unstable = member( 1 );
        final CoreMember stable1 = member( 2 );
        final CoreMember stable2 = member( 3 );

        final CoreMember[] allMembers = {leader, unstable, stable1, stable2};
        final CoreMember[] fewerMembers = {leader, stable1, stable2};

        RaftTestFixture fixture = new RaftTestFixture( net, 3, allMembers );

        // when
        fixture.members().withId( leader ).raftInstance().bootstrapWithInitialMembers( new RaftTestGroup( allMembers ) );
        fixture.members().withId( leader ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        fixture.members().withId( leader ).raftInstance().setTargetMembershipSet( new RaftTestGroup( fewerMembers ).getMembers() );
        net.processMessages();

        assertTrue( fixture.members().withId( leader ).raftInstance().isLeader() );
        assertThat( fixture.members().withIds( fewerMembers ), hasCurrentMembers( new RaftTestGroup( fewerMembers ) ) );

        fixture.members().withId( leader ).raftInstance().setTargetMembershipSet( new RaftTestGroup( allMembers ).getMembers() );
        net.processMessages();

        fixture.members().withId( leader ).timeoutService().invokeTimeout( HEARTBEAT );
        net.processMessages();

        // then
        assertTrue( fixture.members().withId( leader ).raftInstance().isLeader() );
        assertThat( fixture.members().withIds( allMembers ), hasCurrentMembers( new RaftTestGroup( allMembers ) ) );
    }

    @Test
    public void shouldElectNewLeaderWhenOldOneAbruptlyLeaves() throws Exception
    {
        DirectNetworking net = new DirectNetworking();

        // given
        final CoreMember leader1 = member( 0 );
        final CoreMember leader2 = member( 1 );
        final CoreMember stable = member( 2 );

        final CoreMember[] initialMembers = {leader1, leader2, stable};

        RaftTestFixture fixture = new RaftTestFixture( net, 2, initialMembers );

        fixture.members().withId( leader1 ).raftInstance().bootstrapWithInitialMembers( new RaftTestGroup( initialMembers ) );

        fixture.members().withId( leader1 ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        // when
        net.disconnect( leader1 );
        fixture.members().withId( leader2 ).timeoutService().invokeTimeout( ELECTION );
        net.processMessages();

        // then
        assertTrue( fixture.members().withId( leader2 ).raftInstance().isLeader() );
        assertFalse( fixture.members().withId( stable ).raftInstance().isLeader() );
        assertEquals( 1, fixture.members().withIds( leader2, stable ).withRole( LEADER ).size() );
        assertEquals( 1, fixture.members().withIds( leader2, stable ).withRole( FOLLOWER ).size() );
    }

    private Matcher<? super RaftTestFixture.Members> hasCurrentMembers( final RaftTestGroup raftGroup )
    {
        return new TypeSafeMatcher<RaftTestFixture.Members>()
        {
            @Override
            protected boolean matchesSafely( RaftTestFixture.Members members )
            {
                for ( RaftTestFixture.MemberFixture finalMember : members )
                {
                    if ( !raftGroup.equals( new RaftTestGroup( finalMember.raftInstance().replicationMembers() ) ) )
                    {
                        return false;
                    }
                }
                return true;
            }

            @Override
            public void describeTo( Description description )
            {
                description.appendText( "Raft group: " ).appendValue( raftGroup );
            }
        };
    }
}
