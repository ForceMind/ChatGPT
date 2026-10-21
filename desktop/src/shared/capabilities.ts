export type SkillParameter = { id: string; label: string; required: boolean; default?: string };
export type SkillDefinition = {
  id: string; name: string; category: string; description: string; inputs: string[];
  parameters: SkillParameter[]; steps: string[]; instructions: string;
  outputSections: string[]; checks: string[];
  examples: { request: string; expected: string }[]; internetPriority: boolean;
};
export type AgentSummary = {
  id: string; name: string; description: string; category: string;
  internetPriority: boolean; sourcePath: string; sourceURL: string;
};
export type CapabilitySnapshot = {
  skills: SkillDefinition[]; agents: AgentSummary[];
  sourceRepo: string; sourceCommit: string; license: string;
  favorites?: string[];
};
export type TaskPromptOptions = {
  skillId?: string; agentId?: string; language?: string; instructions?: string;
  files: { name: string; mime: string; size: number }[];
};
