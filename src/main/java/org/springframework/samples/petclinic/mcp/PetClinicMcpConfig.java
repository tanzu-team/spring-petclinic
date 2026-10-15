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
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.WebMvcSseServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * Spring configuration registering the MCP SSE transport, routing endpoints, and the
 * synchronous MCP server.
 */
@Configuration(proxyBeanMethods = false)
public class PetClinicMcpConfig {

	@Bean
	@ConditionalOnMissingBean
	public ObjectMapper objectMapper() {
		ObjectMapper mapper = new ObjectMapper();
		mapper.findAndRegisterModules();
		return mapper;
	}

	@Bean
	public WebMvcSseServerTransport webMvcSseServerTransport(ObjectMapper objectMapper) {
		return new WebMvcSseServerTransport(objectMapper, "/mcp/message", "/sse");
	}

	@Bean
	public RouterFunction<ServerResponse> mcpRouterFunction(WebMvcSseServerTransport transport) {
		return transport.getRouterFunction();
	}

	@Bean
	public McpSyncServer mcpSyncServer(WebMvcSseServerTransport transport, PetClinicMcpTools tools) {
		McpServer.SyncSpec syncSpec = McpServer.sync(transport)
			.serverInfo("spring-petclinic-mcp", "1.0.0")
			.capabilities(McpSchema.ServerCapabilities.builder().tools(true).build());

		tools.registerTools(syncSpec);

		return syncSpec.build();
	}

}
