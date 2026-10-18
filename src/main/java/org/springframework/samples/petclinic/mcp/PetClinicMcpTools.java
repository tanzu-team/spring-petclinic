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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.samples.petclinic.owner.Owner;
import org.springframework.samples.petclinic.owner.OwnerRepository;
import org.springframework.samples.petclinic.owner.Pet;
import org.springframework.samples.petclinic.owner.PetType;
import org.springframework.samples.petclinic.owner.PetTypeRepository;
import org.springframework.samples.petclinic.owner.Visit;
import org.springframework.samples.petclinic.vet.Specialty;
import org.springframework.samples.petclinic.vet.Vet;
import org.springframework.samples.petclinic.vet.VetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Service providing MCP tool handlers for Owner Management, Pet Records, Visit
 * Scheduling, and the Veterinarian Directory.
 */
@Service
public class PetClinicMcpTools {

	private final OwnerRepository ownerRepository;

	private final PetTypeRepository petTypeRepository;

	private final VetRepository vetRepository;

	private final ObjectMapper objectMapper;

	private final McpJsonMapper mcpJsonMapper;

	public PetClinicMcpTools(OwnerRepository ownerRepository, PetTypeRepository petTypeRepository,
			VetRepository vetRepository, ObjectMapper objectMapper, McpJsonMapper mcpJsonMapper) {
		this.ownerRepository = ownerRepository;
		this.petTypeRepository = petTypeRepository;
		this.vetRepository = vetRepository;
		this.objectMapper = objectMapper;
		this.mcpJsonMapper = mcpJsonMapper;
	}

	public void registerTools(McpServer.SyncSpecification<?> syncSpec) {
		// Area 1: Pet Owner Management
		registerSearchOwners(syncSpec);
		registerFindOwnersByPetName(syncSpec);
		registerGetOwnerDetails(syncSpec);
		registerAddOwner(syncSpec);
		registerUpdateOwner(syncSpec);

		// Area 2: Pet Records Management
		registerListPetTypes(syncSpec);
		registerAddPet(syncSpec);
		registerUpdatePet(syncSpec);

		// Area 3: Visit & Appointment Scheduling
		registerScheduleVisit(syncSpec);
		registerGetPetVisits(syncSpec);

		// Area 4: Veterinarian Directory
		registerListVets(syncSpec);
	}

	// -------------------------------------------------------------------------
	// Area 1: Pet Owner Management
	// -------------------------------------------------------------------------

	private void registerSearchOwners(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {
						"lastName": {
							"type": "string",
							"description": "Last name prefix of the owner to search for. Leave blank to return all owners."
						}
					}
				}
				""";
		McpSchema.Tool tool = tool("search_owners",
				"Search for pet owners by last name prefix, or retrieve all owners if left blank.", schema);
		syncSpec.tool(tool, wrap(this::searchOwners));
	}

	@Transactional(readOnly = true)
	public McpSchema.CallToolResult searchOwners(Map<String, Object> args) {
		String lastName = getString(args, "lastName", "");
		Page<Owner> page = this.ownerRepository.findByLastNameStartingWith(lastName, PageRequest.of(0, 100));

		List<Map<String, Object>> result = new ArrayList<>();
		for (Owner owner : page.getContent()) {
			result.add(ownerSummary(owner));
		}
		return successResult(result);
	}

	private void registerFindOwnersByPetName(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {
						"petName": {
							"type": "string",
							"description": "Full or partial name of the pet to search for (case-insensitive)."
						}
					},
					"required": ["petName"]
				}
				""";
		McpSchema.Tool tool = tool("find_owners_by_pet_name",
				"Find the owner(s) of a pet by the pet's name. Use this when you know a pet's name and need its owner, then call get_owner_details with the returned owner id.",
				schema);
		syncSpec.tool(tool, wrap(this::findOwnersByPetName));
	}

	@Transactional(readOnly = true)
	public McpSchema.CallToolResult findOwnersByPetName(Map<String, Object> args) {
		String petName = getRequiredString(args, "petName");
		Page<Owner> page = this.ownerRepository.findByPetNameContainingIgnoreCase(petName, PageRequest.of(0, 100));

		List<Map<String, Object>> result = new ArrayList<>();
		for (Owner owner : page.getContent()) {
			result.add(ownerSummary(owner));
		}
		return successResult(result);
	}

	private void registerGetOwnerDetails(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {
						"ownerId": {
							"type": "integer",
							"description": "Unique identifier of the owner."
						}
					},
					"required": ["ownerId"]
				}
				""";
		McpSchema.Tool tool = tool("get_owner_details",
				"Retrieve comprehensive details for an owner including contact information, registered pets, and visit history.",
				schema);
		syncSpec.tool(tool, wrap(this::getOwnerDetails));
	}

	@Transactional(readOnly = true)
	public McpSchema.CallToolResult getOwnerDetails(Map<String, Object> args) {
		int ownerId = getInt(args, "ownerId");
		Owner owner = this.ownerRepository.findById(ownerId)
			.orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId));

		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", owner.getId());
		map.put("firstName", owner.getFirstName());
		map.put("lastName", owner.getLastName());
		map.put("address", owner.getAddress());
		map.put("city", owner.getCity());
		map.put("telephone", owner.getTelephone());

		List<Map<String, Object>> pets = new ArrayList<>();
		for (Pet pet : owner.getPets()) {
			Map<String, Object> petMap = new LinkedHashMap<>();
			petMap.put("id", pet.getId());
			petMap.put("name", pet.getName());
			petMap.put("birthDate", pet.getBirthDate() != null ? pet.getBirthDate().toString() : null);
			petMap.put("type", pet.getType() != null ? pet.getType().getName() : "unknown");

			List<Map<String, Object>> visits = new ArrayList<>();
			for (Visit visit : pet.getVisits()) {
				Map<String, Object> vMap = new LinkedHashMap<>();
				vMap.put("id", visit.getId());
				vMap.put("date", visit.getDate() != null ? visit.getDate().toString() : null);
				vMap.put("description", visit.getDescription());
				visits.add(vMap);
			}
			petMap.put("visits", visits);
			pets.add(petMap);
		}
		map.put("pets", pets);
		return successResult(map);
	}

	private void registerAddOwner(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {
						"firstName": { "type": "string", "description": "First name of the owner." },
						"lastName": { "type": "string", "description": "Last name of the owner." },
						"address": { "type": "string", "description": "Street address of the owner." },
						"city": { "type": "string", "description": "City of the owner." },
						"telephone": { "type": "string", "description": "10-digit telephone number (digits only)." }
					},
					"required": ["firstName", "lastName", "address", "city", "telephone"]
				}
				""";
		McpSchema.Tool tool = tool("add_owner", "Register a new pet owner in the clinic.", schema);
		syncSpec.tool(tool, wrap(this::addOwner));
	}

	@Transactional
	public McpSchema.CallToolResult addOwner(Map<String, Object> args) {
		String firstName = getRequiredString(args, "firstName");
		String lastName = getRequiredString(args, "lastName");
		String address = getRequiredString(args, "address");
		String city = getRequiredString(args, "city");
		String telephone = getRequiredString(args, "telephone");

		if (!telephone.matches("\\d{10}")) {
			throw new IllegalArgumentException("Telephone must be exactly 10 digits");
		}

		Owner owner = new Owner();
		owner.setFirstName(firstName);
		owner.setLastName(lastName);
		owner.setAddress(address);
		owner.setCity(city);
		owner.setTelephone(telephone);

		this.ownerRepository.save(owner);

		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("message", "Owner created successfully");
		resp.put("ownerId", owner.getId());
		resp.put("firstName", owner.getFirstName());
		resp.put("lastName", owner.getLastName());
		resp.put("address", owner.getAddress());
		resp.put("city", owner.getCity());
		resp.put("telephone", owner.getTelephone());
		return successResult(resp);
	}

	private void registerUpdateOwner(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {
						"ownerId": { "type": "integer", "description": "ID of the owner to update." },
						"firstName": { "type": "string", "description": "Updated first name." },
						"lastName": { "type": "string", "description": "Updated last name." },
						"address": { "type": "string", "description": "Updated street address." },
						"city": { "type": "string", "description": "Updated city." },
						"telephone": { "type": "string", "description": "Updated 10-digit telephone number." }
					},
					"required": ["ownerId"]
				}
				""";
		McpSchema.Tool tool = tool("update_owner", "Update contact or profile information for an existing pet owner.",
				schema);
		syncSpec.tool(tool, wrap(this::updateOwner));
	}

	@Transactional
	public McpSchema.CallToolResult updateOwner(Map<String, Object> args) {
		int ownerId = getInt(args, "ownerId");
		Owner owner = this.ownerRepository.findById(ownerId)
			.orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId));

		String firstName = getString(args, "firstName", null);
		if (StringUtils.hasText(firstName)) {
			owner.setFirstName(firstName.trim());
		}
		String lastName = getString(args, "lastName", null);
		if (StringUtils.hasText(lastName)) {
			owner.setLastName(lastName.trim());
		}
		String address = getString(args, "address", null);
		if (StringUtils.hasText(address)) {
			owner.setAddress(address.trim());
		}
		String city = getString(args, "city", null);
		if (StringUtils.hasText(city)) {
			owner.setCity(city.trim());
		}
		String telephone = getString(args, "telephone", null);
		if (StringUtils.hasText(telephone)) {
			telephone = telephone.trim();
			if (!telephone.matches("\\d{10}")) {
				throw new IllegalArgumentException("Telephone must be exactly 10 digits");
			}
			owner.setTelephone(telephone);
		}

		this.ownerRepository.save(owner);

		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("message", "Owner updated successfully");
		resp.put("ownerId", owner.getId());
		resp.put("firstName", owner.getFirstName());
		resp.put("lastName", owner.getLastName());
		resp.put("address", owner.getAddress());
		resp.put("city", owner.getCity());
		resp.put("telephone", owner.getTelephone());
		return successResult(resp);
	}

	// -------------------------------------------------------------------------
	// Area 2: Pet Records Management
	// -------------------------------------------------------------------------

	private void registerListPetTypes(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {}
				}
				""";
		McpSchema.Tool tool = tool("list_pet_types",
				"List all supported pet types (e.g. cat, dog, bird, hamster, lizard, snake).", schema);
		syncSpec.tool(tool, wrap(this::listPetTypes));
	}

	@Transactional(readOnly = true)
	public McpSchema.CallToolResult listPetTypes(Map<String, Object> args) {
		List<PetType> types = this.petTypeRepository.findPetTypes();
		List<Map<String, Object>> result = new ArrayList<>();
		for (PetType type : types) {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("id", type.getId());
			map.put("name", type.getName());
			result.add(map);
		}
		return successResult(result);
	}

	private void registerAddPet(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {
						"ownerId": { "type": "integer", "description": "ID of the owner who owns the pet." },
						"name": { "type": "string", "description": "Name of the pet." },
						"birthDate": { "type": "string", "description": "Birth date in YYYY-MM-DD format." },
						"petType": { "type": "string", "description": "Type of pet (e.g. 'cat', 'dog', 'lizard')." }
					},
					"required": ["ownerId", "name", "birthDate", "petType"]
				}
				""";
		McpSchema.Tool tool = tool("add_pet", "Register a new pet for an existing owner.", schema);
		syncSpec.tool(tool, wrap(this::addPet));
	}

	@Transactional
	public McpSchema.CallToolResult addPet(Map<String, Object> args) {
		int ownerId = getInt(args, "ownerId");
		Owner owner = this.ownerRepository.findById(ownerId)
			.orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId));

		String name = getRequiredString(args, "name");
		if (owner.getPet(name, true) != null) {
			throw new IllegalArgumentException("Pet with name '" + name + "' already exists for this owner.");
		}

		String birthDateStr = getRequiredString(args, "birthDate");
		LocalDate birthDate = LocalDate.parse(birthDateStr);
		String petTypeName = getRequiredString(args, "petType");
		PetType petType = findPetType(petTypeName);

		Pet pet = new Pet();
		pet.setName(name);
		pet.setBirthDate(birthDate);
		pet.setType(petType);

		owner.addPet(pet);
		this.ownerRepository.save(owner);

		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("message", "Pet added successfully");
		resp.put("petId", pet.getId());
		resp.put("name", pet.getName());
		resp.put("type", petType.getName());
		resp.put("ownerId", owner.getId());
		return successResult(resp);
	}

	private void registerUpdatePet(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {
						"ownerId": { "type": "integer", "description": "ID of the owner." },
						"petId": { "type": "integer", "description": "ID of the pet to update." },
						"name": { "type": "string", "description": "Updated name of the pet." },
						"birthDate": { "type": "string", "description": "Updated birth date (YYYY-MM-DD)." },
						"petType": { "type": "string", "description": "Updated pet type name." }
					},
					"required": ["ownerId", "petId"]
				}
				""";
		McpSchema.Tool tool = tool("update_pet", "Update details of an existing pet.", schema);
		syncSpec.tool(tool, wrap(this::updatePet));
	}

	@Transactional
	public McpSchema.CallToolResult updatePet(Map<String, Object> args) {
		int ownerId = getInt(args, "ownerId");
		int petId = getInt(args, "petId");

		Owner owner = this.ownerRepository.findById(ownerId)
			.orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId));

		Pet pet = owner.getPet(petId);
		if (pet == null) {
			throw new IllegalArgumentException("Pet with id " + petId + " not found for owner " + ownerId);
		}

		String name = getString(args, "name", null);
		if (StringUtils.hasText(name)) {
			pet.setName(name.trim());
		}
		String birthDateStr = getString(args, "birthDate", null);
		if (StringUtils.hasText(birthDateStr)) {
			pet.setBirthDate(LocalDate.parse(birthDateStr.trim()));
		}
		String petTypeName = getString(args, "petType", null);
		if (StringUtils.hasText(petTypeName)) {
			pet.setType(findPetType(petTypeName.trim()));
		}

		this.ownerRepository.save(owner);

		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("message", "Pet updated successfully");
		resp.put("petId", pet.getId());
		resp.put("name", pet.getName());
		resp.put("birthDate", pet.getBirthDate() != null ? pet.getBirthDate().toString() : null);
		resp.put("type", pet.getType() != null ? pet.getType().getName() : null);
		return successResult(resp);
	}

	// -------------------------------------------------------------------------
	// Area 3: Visit & Appointment Scheduling
	// -------------------------------------------------------------------------

	private void registerScheduleVisit(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {
						"ownerId": { "type": "integer", "description": "ID of the owner." },
						"petId": { "type": "integer", "description": "ID of the pet." },
						"visitDate": { "type": "string", "description": "Date of the visit (YYYY-MM-DD)." },
						"description": { "type": "string", "description": "Reason or medical notes for the visit." }
					},
					"required": ["ownerId", "petId", "visitDate", "description"]
				}
				""";
		McpSchema.Tool tool = tool("schedule_visit", "Schedule a veterinary consultation or clinical visit for a pet.",
				schema);
		syncSpec.tool(tool, wrap(this::scheduleVisit));
	}

	@Transactional
	public McpSchema.CallToolResult scheduleVisit(Map<String, Object> args) {
		int ownerId = getInt(args, "ownerId");
		int petId = getInt(args, "petId");

		Owner owner = this.ownerRepository.findById(ownerId)
			.orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId));

		Pet pet = owner.getPet(petId);
		if (pet == null) {
			throw new IllegalArgumentException("Pet with id " + petId + " not found for owner " + ownerId);
		}

		String visitDateStr = getRequiredString(args, "visitDate");
		String description = getRequiredString(args, "description");

		Visit visit = new Visit();
		visit.setDate(LocalDate.parse(visitDateStr));
		visit.setDescription(description);

		pet.addVisit(visit);
		this.ownerRepository.save(owner);

		Map<String, Object> resp = new LinkedHashMap<>();
		resp.put("message", "Visit scheduled successfully");
		resp.put("visitId", visit.getId());
		resp.put("date", visit.getDate().toString());
		resp.put("description", visit.getDescription());
		resp.put("petName", pet.getName());
		resp.put("ownerId", owner.getId());
		return successResult(resp);
	}

	private void registerGetPetVisits(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {
						"ownerId": { "type": "integer", "description": "ID of the owner." },
						"petId": { "type": "integer", "description": "ID of the pet." }
					},
					"required": ["ownerId", "petId"]
				}
				""";
		McpSchema.Tool tool = tool("get_pet_visits", "Retrieve the visit and consultation history for a pet.", schema);
		syncSpec.tool(tool, wrap(this::getPetVisits));
	}

	@Transactional(readOnly = true)
	public McpSchema.CallToolResult getPetVisits(Map<String, Object> args) {
		int ownerId = getInt(args, "ownerId");
		int petId = getInt(args, "petId");

		Owner owner = this.ownerRepository.findById(ownerId)
			.orElseThrow(() -> new IllegalArgumentException("Owner not found with id: " + ownerId));

		Pet pet = owner.getPet(petId);
		if (pet == null) {
			throw new IllegalArgumentException("Pet with id " + petId + " not found for owner " + ownerId);
		}

		List<Map<String, Object>> visits = new ArrayList<>();
		for (Visit visit : pet.getVisits()) {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("id", visit.getId());
			map.put("date", visit.getDate() != null ? visit.getDate().toString() : null);
			map.put("description", visit.getDescription());
			visits.add(map);
		}
		return successResult(visits);
	}

	// -------------------------------------------------------------------------
	// Area 4: Veterinarian Directory
	// -------------------------------------------------------------------------

	private void registerListVets(McpServer.SyncSpecification<?> syncSpec) {
		String schema = """
				{
					"type": "object",
					"properties": {}
				}
				""";
		McpSchema.Tool tool = tool("list_vets", "List all veterinarians in the clinic along with their specialties.",
				schema);
		syncSpec.tool(tool, wrap(this::listVets));
	}

	@Transactional(readOnly = true)
	public McpSchema.CallToolResult listVets(Map<String, Object> args) {
		Collection<Vet> vets = this.vetRepository.findAll();
		List<Map<String, Object>> result = new ArrayList<>();
		for (Vet vet : vets) {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("id", vet.getId());
			map.put("firstName", vet.getFirstName());
			map.put("lastName", vet.getLastName());

			List<String> specialties = new ArrayList<>();
			for (Specialty specialty : vet.getSpecialties()) {
				specialties.add(specialty.getName());
			}
			map.put("specialties", specialties);
			result.add(map);
		}
		return successResult(result);
	}

	// -------------------------------------------------------------------------
	// Helper Methods
	// -------------------------------------------------------------------------

	private PetType findPetType(String name) {
		for (PetType type : this.petTypeRepository.findPetTypes()) {
			if (type.getName().equalsIgnoreCase(name)) {
				return type;
			}
		}
		throw new IllegalArgumentException(
				"Pet type '" + name + "' not found. Available types can be queried via list_pet_types.");
	}

	private Map<String, Object> ownerSummary(Owner owner) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", owner.getId());
		map.put("firstName", owner.getFirstName());
		map.put("lastName", owner.getLastName());
		map.put("address", owner.getAddress());
		map.put("city", owner.getCity());
		map.put("telephone", owner.getTelephone());

		List<Map<String, Object>> pets = new ArrayList<>();
		for (Pet pet : owner.getPets()) {
			Map<String, Object> petMap = new LinkedHashMap<>();
			petMap.put("id", pet.getId());
			petMap.put("name", pet.getName());
			petMap.put("type", pet.getType() != null ? pet.getType().getName() : "unknown");
			pets.add(petMap);
		}
		map.put("pets", pets);
		return map;
	}

	private McpSchema.Tool tool(String name, String description, String inputSchema) {
		return McpSchema.Tool.builder()
			.name(name)
			.description(description)
			.inputSchema(this.mcpJsonMapper, inputSchema)
			.build();
	}

	private BiFunction<McpSyncServerExchange, Map<String, Object>, McpSchema.CallToolResult> wrap(
			Function<Map<String, Object>, McpSchema.CallToolResult> handler) {
		return (exchange, args) -> {
			try {
				return handler.apply(args);
			}
			catch (Exception ex) {
				return errorResult("Error: " + ex.getMessage());
			}
		};
	}

	private McpSchema.CallToolResult successResult(Object data) {
		try {
			String json = this.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(data);
			return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(json)), false);
		}
		catch (JsonProcessingException ex) {
			return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(String.valueOf(data))), false);
		}
	}

	private McpSchema.CallToolResult errorResult(String message) {
		return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(message)), true);
	}

	private int getInt(Map<String, Object> args, String key) {
		Object val = args.get(key);
		if (val == null) {
			throw new IllegalArgumentException("Missing required argument: " + key);
		}
		if (val instanceof Number num) {
			return num.intValue();
		}
		return Integer.parseInt(val.toString().trim());
	}

	private String getString(Map<String, Object> args, String key, String defaultValue) {
		Object val = args.get(key);
		if (val == null) {
			return defaultValue;
		}
		return val.toString().trim();
	}

	private String getRequiredString(Map<String, Object> args, String key) {
		Object val = args.get(key);
		if (val == null || val.toString().trim().isEmpty()) {
			throw new IllegalArgumentException("Missing required argument: " + key);
		}
		return val.toString().trim();
	}

}
