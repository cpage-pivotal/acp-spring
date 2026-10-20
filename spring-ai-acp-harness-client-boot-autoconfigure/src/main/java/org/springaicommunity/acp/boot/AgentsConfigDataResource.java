package org.springaicommunity.acp.boot;

import org.springframework.boot.context.config.ConfigDataResource;
import org.springframework.core.io.Resource;

/**
 * One {@code agents.yaml} file, resolved but not yet read.
 */
public class AgentsConfigDataResource extends ConfigDataResource {

	private final String location;

	private final Resource resource;

	AgentsConfigDataResource(String location, Resource resource, boolean optional) {
		super(optional);
		this.location = location;
		this.resource = resource;
	}

	Resource resource() {
		return resource;
	}

	String location() {
		return location;
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof AgentsConfigDataResource that && location.equals(that.location);
	}

	@Override
	public int hashCode() {
		return location.hashCode();
	}

	@Override
	public String toString() {
		return "agents config '" + location + "'";
	}

}
