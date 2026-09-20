package org.thought.acp.observation;

import io.micrometer.common.docs.KeyName;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationConvention;
import io.micrometer.observation.docs.ObservationDocumentation;

/**
 * The observations this library makes, and the tags each one carries.
 *
 * <p>Two, and only two: a turn, and a tool call inside one. Both are things that take time and can
 * fail, which is what an observation is for; a session is neither, and a prompt is an argument.
 *
 * <p>The low-cardinality split is the part worth checking against before adding a tag. {@code
 * acp.runtime} has three values, {@code acp.model} has as many as an operator configured — one,
 * usually — and {@code acp.outcome} has the stop reasons plus a handful of exception names. A
 * session name or a tool call id would have as many values as there are conversations, so both are
 * high-cardinality, where they become span attributes and not meter dimensions.
 */
public enum AcpObservationDocumentation implements ObservationDocumentation {

	/** One prompt turn, from subscription to its single terminal event. */
	TURN {
		@Override
		public Class<? extends ObservationConvention<? extends Observation.Context>> getDefaultConvention() {
			return DefaultAgentTurnObservationConvention.class;
		}

		@Override
		public String getPrefix() {
			return "acp.turn";
		}

		@Override
		public KeyName[] getLowCardinalityKeyNames() {
			return TurnLowCardinalityKeys.values();
		}

		@Override
		public KeyName[] getHighCardinalityKeyNames() {
			return TurnHighCardinalityKeys.values();
		}
	},

	/** One tool call the agent announced, from its start to a terminal status. */
	TOOL_CALL {
		@Override
		public Class<? extends ObservationConvention<? extends Observation.Context>> getDefaultConvention() {
			return DefaultToolCallObservationConvention.class;
		}

		@Override
		public String getPrefix() {
			return "acp.tool.call";
		}

		@Override
		public KeyName[] getLowCardinalityKeyNames() {
			return ToolCallLowCardinalityKeys.values();
		}

		@Override
		public KeyName[] getHighCardinalityKeyNames() {
			return ToolCallHighCardinalityKeys.values();
		}
	};

	public enum TurnLowCardinalityKeys implements KeyName {

		/** The value of {@code spring.acp.runtime}. */
		RUNTIME {
			@Override
			public String asString() {
				return "acp.runtime";
			}
		},

		/**
		 * The model the session is really using — what the negotiated tier applied, not what was
		 * requested. {@code unknown} when the agent kept its own default and never said which.
		 */
		MODEL {
			@Override
			public String asString() {
				return "acp.model";
			}
		},

		/** {@code named} or {@code ephemeral}: whether the conversation outlives this turn. */
		SESSION_KIND {
			@Override
			public String asString() {
				return "acp.session.kind";
			}
		},

		/** The agent's stop reason, or the exception's simple name when the turn did not reach one. */
		OUTCOME {
			@Override
			public String asString() {
				return "acp.outcome";
			}
		}
	}

	public enum TurnHighCardinalityKeys implements KeyName {

		/** The caller's name for the conversation. One value per conversation. */
		SESSION_NAME {
			@Override
			public String asString() {
				return "acp.session.name";
			}
		},

		/** Context window consumed, as of the last {@code usage_update} in this turn. */
		CONTEXT_USED {
			@Override
			public String asString() {
				return "acp.context.used";
			}

			@Override
			public boolean isRequired() {
				return false;
			}
		},

		/** The context window's size, from the same update. */
		CONTEXT_SIZE {
			@Override
			public String asString() {
				return "acp.context.size";
			}

			@Override
			public boolean isRequired() {
				return false;
			}
		},

		/** What the agent says the session has cost so far, when it says. */
		COST {
			@Override
			public String asString() {
				return "acp.cost";
			}

			@Override
			public boolean isRequired() {
				return false;
			}
		}
	}

	public enum ToolCallLowCardinalityKeys implements KeyName {

		RUNTIME {
			@Override
			public String asString() {
				return "acp.runtime";
			}
		},

		/** The ACP {@code ToolKind}: read, edit, execute, think, fetch, other. */
		KIND {
			@Override
			public String asString() {
				return "acp.tool.kind";
			}
		},

		/** The terminal {@code ToolCallStatus}, or {@code unfinished} for a call the turn outlived. */
		STATUS {
			@Override
			public String asString() {
				return "acp.tool.status";
			}
		}
	}

	public enum ToolCallHighCardinalityKeys implements KeyName {

		/**
		 * The agent's own title for the call — prose written for a human, which is all ACP
		 * guarantees. High-cardinality for exactly that reason.
		 */
		TITLE {
			@Override
			public String asString() {
				return "acp.tool.title";
			}
		},

		TOOL_CALL_ID {
			@Override
			public String asString() {
				return "acp.tool.id";
			}
		}
	}
}
