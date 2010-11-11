/**
 * Copyright (c) 2002-2010 "Neo Technology,"
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

package org.neo4j.server.rest;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.neo4j.server.NeoServer;
import org.neo4j.server.ServerTestUtils;
import org.neo4j.server.database.DatabaseBlockedException;
import org.neo4j.server.rest.domain.GraphDbHelper;
import org.neo4j.server.rest.domain.JsonHelper;
import org.neo4j.server.rest.domain.RelationshipRepresentationTest;

import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class RetrieveRelationshipsFromNodeFunctionalTest {

    private static long nodeWithRelationships;
    private static long nodeWithoutRelationships;
    private static long nonExistingNode;
    private static GraphDbHelper helper;
    public static NeoServer server;

    @BeforeClass
    public static void startServer() throws DatabaseBlockedException {
        server = ServerTestUtils.initializeServerWithRandomTemporaryDatabaseDirectory();
        helper = new GraphDbHelper(server.database());
        nodeWithRelationships = helper.createNode();
        helper.createRelationship("LIKES", nodeWithRelationships, helper.createNode());
        helper.createRelationship("LIKES", helper.createNode(), nodeWithRelationships);
        helper.createRelationship("HATES", nodeWithRelationships, helper.createNode());
        nodeWithoutRelationships = helper.createNode();
        nonExistingNode = nodeWithoutRelationships * 100;
    }

    @AfterClass
    public static void stopServer() throws Exception {
        ServerTestUtils.nukeServer();
    }

    private ClientResponse sendRetrieveRequestToServer(long nodeId, String path) {
        WebResource resource = Client.create().resource(server.restApiUri() + "node/" + nodeId + "/relationships" + path);
        return resource.accept(MediaType.APPLICATION_JSON).get(ClientResponse.class);
    }

    private void verifyRelReps(int expectedSize, String json) {
        List<Map<String, Object>> relreps = JsonHelper.jsonToListOfRelationshipRepresentations(json);
        assertEquals(expectedSize, relreps.size());
        for (Map<String, Object> relrep : relreps) {
            RelationshipRepresentationTest.verifySerialisation(relrep);
        }
    }

    @Test
    public void shouldRespondWith200AndListOfRelationshipRepresentationsWhenGettingAllRelationshipsForANode() {
        ClientResponse response = sendRetrieveRequestToServer(nodeWithRelationships, "/all");
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
        verifyRelReps(3, response.getEntity(String.class));
    }

    @Test
    public void shouldRespondWith200AndListOfRelationshipRepresentationsWhenGettingIncomingRelationshipsForANode() {
        ClientResponse response = sendRetrieveRequestToServer(nodeWithRelationships, "/in");
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
        verifyRelReps(1, response.getEntity(String.class));
    }

    @Test
    public void shouldRespondWith200AndListOfRelationshipRepresentationsWhenGettingOutgoingRelationshipsForANode() {
        ClientResponse response = sendRetrieveRequestToServer(nodeWithRelationships, "/out");
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
        verifyRelReps(2, response.getEntity(String.class));
    }

    @Test
    public void shouldRespondWith200AndListOfRelationshipRepresentationsWhenGettingAllTypedRelationshipsForANode() {
        ClientResponse response = sendRetrieveRequestToServer(nodeWithRelationships, "/all/LIKES&HATES");
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
        verifyRelReps(3, response.getEntity(String.class));
    }

    @Test
    public void shouldRespondWith200AndListOfRelationshipRepresentationsWhenGettingIncomingTypedRelationshipsForANode() {
        ClientResponse response = sendRetrieveRequestToServer(nodeWithRelationships, "/in/LIKES");
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
        verifyRelReps(1, response.getEntity(String.class));
    }

    @Test
    public void shouldRespondWith200AndListOfRelationshipRepresentationsWhenGettingOutgoingTypedRelationshipsForANode() {
        ClientResponse response = sendRetrieveRequestToServer(nodeWithRelationships, "/out/HATES");
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
        verifyRelReps(1, response.getEntity(String.class));
    }

    @Test
    public void shouldRespondWith200AndEmptyListOfRelationshipRepresentationsWhenGettingAllRelationshipsForANodeWithoutRelationships() {
        ClientResponse response = sendRetrieveRequestToServer(nodeWithoutRelationships, "/all");
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
        verifyRelReps(0, response.getEntity(String.class));
    }

    @Test
    public void shouldRespondWith200AndEmptyListOfRelationshipRepresentationsWhenGettingIncomingRelationshipsForANodeWithoutRelationships() {
        ClientResponse response = sendRetrieveRequestToServer(nodeWithoutRelationships, "/in");
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
        verifyRelReps(0, response.getEntity(String.class));
    }

    @Test
    public void shouldRespondWith200AndEmptyListOfRelationshipRepresentationsWhenGettingOutgoingRelationshipsForANodeWithoutRelationships() {
        ClientResponse response = sendRetrieveRequestToServer(nodeWithoutRelationships, "/out");
        assertEquals(200, response.getStatus());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getType());
        verifyRelReps(0, response.getEntity(String.class));
    }

    @Test
    public void shouldRespondWith404WhenGettingAllRelationshipsForNonExistingNode() {
        ClientResponse response = sendRetrieveRequestToServer(nonExistingNode, "/all");
        assertEquals(404, response.getStatus());
    }

    @Test
    public void shouldRespondWith404WhenGettingIncomingRelationshipsForNonExistingNode() {
        ClientResponse response = sendRetrieveRequestToServer(nonExistingNode, "/in");
        assertEquals(404, response.getStatus());
    }

    @Test
    public void shouldRespondWith404WhenGettingOutgoingRelationshipsForNonExistingNode() {
        ClientResponse response = sendRetrieveRequestToServer(nonExistingNode, "/out");
        assertEquals(404, response.getStatus());
    }

    @Test
    public void shouldGet200WhenRetrievingValidRelationship() throws DatabaseBlockedException {
        long relationshipId = helper.createRelationship("LIKES");

        ClientResponse response = Client.create().resource(server.restApiUri() + "relationship/" + relationshipId).get(ClientResponse.class);

        assertEquals(200, response.getStatus());
    }

    @Test
    public void shouldGetARelationshipRepresentationInJsonWhenRetrievingValidRelationship() throws Exception {
        long relationshipId = helper.createRelationship("LIKES");

        ClientResponse response = Client.create().resource(server.restApiUri() + "relationship/" + relationshipId).accept(
                MediaType.APPLICATION_JSON_TYPE).get(ClientResponse.class);

        String entity = response.getEntity(String.class);
        assertNotNull(entity);
        isLegalJson(entity);
    }

    private void isLegalJson(String entity) throws IOException {
        JsonHelper.jsonToMap(entity);
    }
}
