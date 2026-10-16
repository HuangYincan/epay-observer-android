package dev.epay.observer.storage

import dev.epay.observer.parsing.ParseResult

object OutboundPolicy {
    fun permits(binding: BindingSnapshot, candidate: ParseResult.Candidate): Boolean =
        binding.sourceClass == BindingSnapshot.SourceClass.SYNTHETIC && candidate.provider == binding.provider &&
            candidate.ruleVersion in setOf("fixture-wx-v1", "fixture-ali-v1")
}
