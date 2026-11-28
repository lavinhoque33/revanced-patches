/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.immutable.ImmutableExceptionHandler
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableTryBlock
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegisterLivenessTest {
    private fun code(body: String): List<Instruction> = ImmutableMethod(
        "LCallback;", "run", emptyList(), "V", AccessFlags.PUBLIC.value,
        null, null, MutableMethodImplementation(8)
    ).toMutable().apply { addInstructions(0, body) }.implementation!!.instructions.toList()

    private fun List<Instruction>.deadAt(index: Int, register: Int) = isRegisterDeadAt(index, register)

    @Test fun registerAssignedBeforeAnyReadIsDead() {
        val code = code("""
            const/4 v1, 0x0
            invoke-static {v1}, LCallback;->use(I)V
            return-void
        """)

        assertTrue(code.deadAt(0, 1))
    }

    @Test fun registerReadBeforeAssignmentIsAlive() {
        val code = code("""
            invoke-static {v1}, LCallback;->use(I)V
            const/4 v1, 0x0
            return-void
        """)

        assertFalse(code.deadAt(0, 1))
    }

    @Test fun registerReadOnOneBranchIsAlive() {
        val code = code("""
            if-eqz v0, :skip
            const/4 v1, 0x0
            return-void
            :skip
            invoke-static {v1}, LCallback;->use(I)V
            return-void
        """)

        assertFalse(code.deadAt(0, 1))
        assertTrue(code.deadAt(1, 1))
    }

    @Test fun registerAssignedOnEveryBranchIsDead() {
        val code = code("""
            if-eqz v0, :other
            const/4 v1, 0x0
            goto :done
            :other
            const/4 v1, 0x1
            :done
            return-void
        """)

        assertTrue(code.deadAt(0, 1))
    }

    @Test fun registerThatIsNeverTouchedIsDead() {
        val code = code("""
            invoke-static {v0}, LCallback;->use(I)V
            return-void
        """)

        assertTrue(code.deadAt(0, 2))
        assertFalse(code.deadAt(0, 0))
    }

    @Test fun twoAddressOperationReadsItsDestination() {
        val code = code("""
            xor-int/2addr v1, v0
            return-void
        """)

        assertFalse(code.deadAt(0, 1))
    }

    @Test fun typeTestReadsItsSourceAndAssignsItsDestination() {
        val code = code("""
            instance-of v2, v1, LCallback;
            const/4 v1, 0x0
            return-void
        """)

        assertFalse(code.deadAt(0, 1))
        assertTrue(code.deadAt(0, 2))
    }

    @Test fun wideAssignmentCoversBothHalvesAndWideReadCoversBothHalves() {
        val code = code("""
            move-wide v0, v2
            return-void
        """)

        assertFalse(code.deadAt(0, 2))
        assertFalse(code.deadAt(0, 3))
        assertTrue(code.deadAt(0, 1))
    }

    @Test fun checkCastReadsItsOperand() {
        val code = code("""
            check-cast v1, LCallback;
            const/4 v1, 0x0
            return-void
        """)

        assertFalse(code.deadAt(0, 1))
    }

    @Test fun rangeInvokeReadsEveryRegisterOfItsRange() {
        val code = code("""
            invoke-static/range {v1 .. v3}, LCallback;->use(III)V
            const/4 v2, 0x0
            return-void
        """)

        assertFalse(code.deadAt(0, 2))
        assertTrue(code.deadAt(0, 4))
    }

    @Test fun switchFollowsEveryCase() {
        val code = code("""
            packed-switch v0, :cases
            return-void
            :one
            invoke-static {v1}, LCallback;->use(I)V
            return-void
            :two
            const/4 v1, 0x0
            return-void
            :cases
            .packed-switch 0x0
                :one
                :two
            .end packed-switch
        """)

        assertFalse(code.deadAt(0, 1))
    }

    @Test fun handlerOfThrowingInstructionSeesTheRegisterUnassigned() {
        val use = ImmutableMethodReference("LCallback;", "use", listOf("I"), "V")
        val code = listOf<Instruction>(
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference("LCallback;")),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, use),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val handler = ImmutableTryBlock(0, 2, listOf(ImmutableExceptionHandler(null, 3)))

        assertEquals(2, code[0].codeUnits)
        assertFalse(code.isRegisterDeadAt(0, 1, listOf(handler)))
        assertTrue(code.isRegisterDeadAt(0, 1))
    }
}
