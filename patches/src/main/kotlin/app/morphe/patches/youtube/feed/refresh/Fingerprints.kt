/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3387
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 * Ported to RVX by lavinhoque33, 2026-10-16.
 */

package app.morphe.patches.youtube.feed.refresh

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.opcode
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.Opcode

/**
 * Stores when the loaded feed expires (the first field) and when the feed refresh is scheduled
 * (the second field), both in milliseconds.
 *
 * Verified with dexdump of YouTube 21.39.525, 21.13.164 and 20.05.46: the method is unique
 * (the two browse id strings are used only here) and the instruction pattern is the same in all three,
 * with the second addition being 'add-long' (21.13) or 'add-long/2addr' (21.39 and 20.05).
 */
internal object FeedExpirationFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(
        string("FEmemberships_and_purchases"),
        string("FEmembership_detail"),
        opcode(Opcode.ADD_LONG),
        fieldAccess(
            opcode = Opcode.IPUT_WIDE,
            definingClass = "this",
            type = "J",
            location = InstructionLocation.MatchAfterImmediately()
        ),
        anyInstruction(
            opcode(Opcode.ADD_LONG),
            opcode(Opcode.ADD_LONG_2ADDR),
            location = InstructionLocation.MatchAfterWithin(10)
        ),
        fieldAccess(
            opcode = Opcode.IPUT_WIDE,
            definingClass = "this",
            type = "J",
            location = InstructionLocation.MatchAfterImmediately()
        )
    )
)
