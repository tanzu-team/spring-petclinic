/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.samples.petclinic.mcp;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "spring.lifecycle.timeout-per-shutdown-phase=1s")
class PetClinicMcpTests {

	@LocalServerPort
	private int port;

	@Autowired
	private PetClinicMcpTools mcpTools;

	@Autowired
	private ObjectMapper objectMapper;

	private String getText(McpSchema.CallToolResult result) {
		assertThat(result.content()).isNotEmpty();
		assertThat(result.content().get(0)).isInstanceOf(McpSchema.TextContent.class);
		return ((McpSchema.TextContent) result.content().get(0)).text();
	}

	@Test
	@Transactional(readOnly = true)
	void testSearchOwners() {
		McpSchema.CallToolResult result = this.mcpTools.searchOwners(Map.of("lastName", "Davis"));
		assertThat(result.isError()).isFalse();
		String content = getText(result);
		assertThat(content).contains("Davis");
		assertThat(content).contains("Betty");
	}

	@Test
	@Transactional(readOnly = true)
	void testFindOwnersByPetName() {
		McpSchema.CallToolResult result = this.mcpTools.findOwnersByPetName(Map.of("petName", "Basil"));
		assertThat(result.isError()).isFalse();
		String content = getText(result);
		assertThat(content).contains("Betty");
		assertThat(content).contains("Davis");
		assertThat(content).contains("Basil");
	}

	@Test
	@Transactional(readOnly = true)
	void testFindOwnersByPetNameIsCaseInsensitiveAndPartial() {
		McpSchema.CallToolResult result = this.mcpTools.findOwnersByPetName(Map.of("petName", "luck"));
		assertThat(result.isError()).isFalse();
		String content = getText(result);
		assertThat(content).contains("Jeff");
		assertThat(content).contains("Carlos");
		assertThat(content).contains("Lucky");
	}

	@Test
	@Transactional(readOnly = true)
	void testFindOwnersByUnknownPetNameReturnsEmpty() throws Exception {
		McpSchema.CallToolResult result = this.mcpTools.findOwnersByPetName(Map.of("petName", "Rex"));
		assertThat(result.isError()).isFalse();
		JsonNode owners = this.objectMapper.readTree(getText(result));
		assertThat(owners.isArray()).isTrue();
		assertThat(owners).isEmpty();
	}

	@Test
	@Transactional(readOnly = true)
	void testGetOwnerDetails() {
		McpSchema.CallToolResult result = this.mcpTools.getOwnerDetails(Map.of("ownerId", 1));
		assertThat(result.isError()).isFalse();
		String content = getText(result);
		assertThat(content).contains("George");
		assertThat(content).contains("Franklin");
		assertThat(content).contains("Leo");
	}

	@Test
	@Transactional
	void testAddAndUpdateOwner() {
		// Add Owner
		McpSchema.CallToolResult addResult = this.mcpTools.addOwner(Map.of("firstName", "John", "lastName", "Doe",
				"address", "123 Main St", "city", "Springfield", "telephone", "1234567890"));
		assertThat(addResult.isError()).isFalse();
		String addContent = getText(addResult);
		assertThat(addContent).contains("Owner created successfully");

		// Update Owner
		McpSchema.CallToolResult updateResult = this.mcpTools.updateOwner(Map.of("ownerId", 1, "city", "Metropolis"));
		assertThat(updateResult.isError()).isFalse();
		String updateContent = getText(updateResult);
		assertThat(updateContent).contains("Metropolis");
	}

	@Test
	@Transactional(readOnly = true)
	void testListPetTypes() {
		McpSchema.CallToolResult result = this.mcpTools.listPetTypes(Map.of());
		assertThat(result.isError()).isFalse();
		String content = getText(result);
		assertThat(content).contains("cat");
		assertThat(content).contains("dog");
	}

	@Test
	@Transactional
	void testAddAndUpdatePet() {
		// Add Pet
		McpSchema.CallToolResult addPetResult = this.mcpTools
			.addPet(Map.of("ownerId", 1, "name", "Bella", "birthDate", "2022-01-15", "petType", "cat"));
		assertThat(addPetResult.isError()).isFalse();
		String addPetContent = getText(addPetResult);
		assertThat(addPetContent).contains("Pet added successfully");
		assertThat(addPetContent).contains("Bella");

		// Update Pet
		McpSchema.CallToolResult updatePetResult = this.mcpTools
			.updatePet(Map.of("ownerId", 1, "petId", 1, "name", "Leo The Brave"));
		assertThat(updatePetResult.isError()).isFalse();
		String updatePetContent = getText(updatePetResult);
		assertThat(updatePetContent).contains("Leo The Brave");
	}

	@Test
	@Transactional
	void testScheduleVisitAndGetVisits() {
		// Schedule Visit
		McpSchema.CallToolResult visitResult = this.mcpTools.scheduleVisit(
				Map.of("ownerId", 1, "petId", 1, "visitDate", "2026-11-20", "description", "Regular dental checkup"));
		assertThat(visitResult.isError()).isFalse();
		String visitContent = getText(visitResult);
		assertThat(visitContent).contains("Visit scheduled successfully");
		assertThat(visitContent).contains("Regular dental checkup");

		// Get Visits
		McpSchema.CallToolResult getVisitsResult = this.mcpTools.getPetVisits(Map.of("ownerId", 1, "petId", 1));
		assertThat(getVisitsResult.isError()).isFalse();
		String getVisitsContent = getText(getVisitsResult);
		assertThat(getVisitsContent).contains("Regular dental checkup");
	}

	@Test
	@Transactional(readOnly = true)
	void testListVets() {
		McpSchema.CallToolResult result = this.mcpTools.listVets(Map.of());
		assertThat(result.isError()).isFalse();
		String content = getText(result);
		assertThat(content).contains("James");
		assertThat(content).contains("Carter");
		assertThat(content).contains("Helen");
		assertThat(content).contains("radiology");
	}

	@Test
	void testStreamableHttpEndpointAvailability() throws Exception {
		URL url = URI.create("http://localhost:" + this.port + "/mcp").toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("POST");
		conn.setRequestProperty("Content-Type", "application/json");
		conn.setRequestProperty("Accept", "application/json, text/event-stream");
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(5000);
		conn.setDoOutput(true);

		String request = """
				{
					"jsonrpc": "2.0",
					"id": 1,
					"method": "initialize",
					"params": {
						"protocolVersion": "2025-06-18",
						"capabilities": {},
						"clientInfo": { "name": "petclinic-test", "version": "1.0.0" }
					}
				}
				""";
		try (OutputStream out = conn.getOutputStream()) {
			out.write(request.getBytes(StandardCharsets.UTF_8));
		}

		try {
			assertThat(conn.getResponseCode()).isEqualTo(200);
			assertThat(conn.getHeaderField("Mcp-Session-Id")).isNotBlank();

			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
				StringBuilder sb = new StringBuilder();
				String line;
				while ((line = reader.readLine()) != null) {
					sb.append(line);
				}
				assertThat(sb.toString()).contains("spring-petclinic-mcp");
			}
		}
		finally {
			conn.disconnect();
		}
	}

	@Test
	void testSseEndpointAvailability() throws Exception {
		URL url = URI.create("http://localhost:" + this.port + "/sse").toURL();
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestMethod("GET");
		conn.setConnectTimeout(5000);
		conn.setReadTimeout(5000);

		try {
			assertThat(conn.getResponseCode()).isEqualTo(200);
			assertThat(conn.getContentType()).contains("text/event-stream");

			try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
				StringBuilder sb = new StringBuilder();
				for (int i = 0; i < 5; i++) {
					String line = reader.readLine();
					if (line != null) {
						sb.append(line).append("\n");
						if (line.contains("/mcp/message")) {
							break;
						}
					}
				}
				assertThat(sb.toString()).contains("endpoint");
				assertThat(sb.toString()).contains("/mcp/message");
			}
		}
		finally {
			conn.disconnect();
		}
	}

}
