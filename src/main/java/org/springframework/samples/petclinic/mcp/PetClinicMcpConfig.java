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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransportProvider;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Spring configuration registering the MCP SSE (legacy) and Streamable HTTP transports,
 * their routing endpoints, and the synchronous MCP servers backed by them.
 */
@Configuration(proxyBeanMethods = false)
public class PetClinicMcpConfig {

	private static final String SERVER_NAME = "spring-petclinic-mcp";

	private static final String SERVER_VERSION = "1.0.0";

	@Bean
	@ConditionalOnMissingBean
	public ObjectMapper objectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.findAndRegisterModules();
		return mapper;
	}

	@Bean
	public McpJsonMapper mcpJsonMapper(ObjectMapper objectMapper) {
		return new JacksonMcpJsonMapper(objectMapper);
	}

	@Bean
	public WebMvcSseServerTransportProvider webMvcSseServerTransportProvider(McpJsonMapper mcpJsonMapper) {
		return WebMvcSseServerTransportProvider.builder()
			.jsonMapper(mcpJsonMapper)
			.messageEndpoint("/mcp/message")
			.sseEndpoint("/sse")
			.build();
	}

	@Bean
	public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
			McpJsonMapper mcpJsonMapper) {
		return WebMvcStreamableServerTransportProvider.builder().jsonMapper(mcpJsonMapper).mcpEndpoint("/mcp").build();
	}

	@Bean
	public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcSseServerTransportProvider sseTransport,
			WebMvcStreamableServerTransportProvider streamableTransport) {
		return sseTransport.getRouterFunction().and(streamableTransport.getRouterFunction());
	}

	@Bean
	public McpSyncServer mcpSseServer(WebMvcSseServerTransportProvider transport, PetClinicMcpTools tools) {
		McpServer.SyncSpecification<?> syncSpec = McpServer.sync(transport)
			.serverInfo(SERVER_NAME, SERVER_VERSION)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());

		tools.registerTools(syncSpec);

		return syncSpec.build();
	}

	@Bean
	public McpSyncServer mcpStreamableServer(WebMvcStreamableServerTransportProvider transport,
			PetClinicMcpTools tools) {
		McpServer.SyncSpecification<?> syncSpec = McpServer.sync(transport)
			.serverInfo(SERVER_NAME, SERVER_VERSION)
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());

		tools.registerTools(syncSpec);

		return syncSpec.build();
	}

}
