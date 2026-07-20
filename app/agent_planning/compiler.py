from __future__ import annotations

import hashlib
import json

from .capability_registry import CapabilitySpecRegistry, get_capability_registry
from .contracts import CompiledPlan, PlanNode, RequestPlan


class PlanCompiler:
    def __init__(self, registry: CapabilitySpecRegistry | None = None) -> None:
        self.registry = registry or get_capability_registry()

    def compile(self, plan: RequestPlan) -> CompiledPlan:
        output_facts = self.registry.required_output_facts(plan)
        capabilities = self.registry.compile_capabilities(output_facts)
        required_facts = set(output_facts)
        nodes = []
        for capability in capabilities:
            spec = self.registry.get(capability)
            if spec.completion_fact:
                required_facts.add(spec.completion_fact)
            nodes.append(PlanNode(
                capability=capability,
                capability_version=spec.version,
                requires=set(spec.requires),
                produces=set(spec.produces),
                tool_name=spec.tool_name,
                policy_action=spec.policy_action,
                verifier=spec.verifier_name,
                retry_policy=spec.retry_policy,
                answer_mode=spec.answer_mode,
            ))
        canonical = json.dumps(
            plan.model_dump(mode="json"),
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        plan_id = "PLAN_" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()[:16]
        return CompiledPlan(
            plan_id=plan_id,
            request_plan_version=plan.schema_version,
            capability_registry_version=self.registry.version,
            intent=plan.intent,
            goal=plan.goal,
            nodes=nodes,
            required_facts=required_facts,
            requested_outputs=set(plan.requested_outputs),
        )
