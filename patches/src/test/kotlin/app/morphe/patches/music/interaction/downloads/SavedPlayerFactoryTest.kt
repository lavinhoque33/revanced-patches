/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.patches.music.interaction.downloads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedPlayerFactoryTest {
    private fun factory(sharedReturn: Boolean): MutableMethod {
        val messageLite = "Lcom/google/protobuf/MessageLite;"
        val inputGuard = if (sharedReturn) "if-eqz p0, :missing" else """
            if-nez p0, :parse
            goto :missing
        """
        val exit = if (sharedReturn) """
            goto :done
            :missing
            const/4 v0, 0x0
            :done
            return-object v0
        """ else """
            return-object v0
            :missing
            const/4 p0, 0x0
            return-object p0
        """
        return ImmutableMethod(
            "LWrapper;", "stock", listOf("[B", "J").map { ImmutableMethodParameter(it, null, null) },
            "LResponse;", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            MutableMethodImplementation(5),
        ).toMutable().apply {
            addInstructionsWithLabels(0, """
                $inputGuard
                :parse
                sget-object v0, LPlayerProto;->a:LPlayerProto;
                invoke-static {p0, v0}, LParser;->parse([B$messageLite)$messageLite
                move-result-object p0
                check-cast p0, LPlayerProto;
                if-eqz p0, :missing
                new-instance v0, LWrapper;
                sget-object v1, LMetadata;->a:LMetadata;
                invoke-direct {v0, p0, p1, p2, v1}, LWrapper;-><init>(LPlayerProto;JLMetadata;)V
                $exit
            """)
        }.let { ImmutableMethod.of(it).toMutable() }
    }

    @Test
    fun sharedReturnIsKeptWhenOnlySavedConstructionIsMarked() = checkFactory(sharedReturn = true)

    @Test
    fun separateNullReturnIsKeptWhenOnlySavedConstructionIsMarked() = checkFactory(sharedReturn = false)

    private fun checkFactory(sharedReturn: Boolean) {
        val stock = factory(sharedReturn)
        val original = stock.implementation!!.instructions.map { it.opcode }
        val (saved, installMarker) = stock.prepareSavedPlayerFactory("LPlayerProto;")
        assertEquals(original, saved.implementation!!.instructions.map { it.opcode })
        assertEquals(SAVED_PLAYER_FACTORY, saved.name)

        val marker = ImmutableFieldReference("LPlayerProto;", "rvxLocalStreamBuild", "Z")
        installMarker(marker)
        assertEquals(original, stock.implementation!!.instructions.map { it.opcode })
        val marked = saved.implementation!!.instructions
        val construct = marked.indexOfFirst { it.getReference<MethodReference>()?.name == "<init>" }
        val mark = construct - 3
        val clear = construct + 2
        val writes = marked.indices.filter { marked[it].getReference<FieldReference>() == marker }
        assertEquals(listOf(mark, clear), writes)
        assertEquals(1, (marked[mark - 1] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(0, (marked[clear - 1] as NarrowLiteralInstruction).narrowLiteral)
        for (write in writes) {
            assertEquals(Opcode.IPUT_BOOLEAN, marked[write].opcode)
            assertEquals(2, (marked[write] as TwoRegisterInstruction).registerB)
        }
        val markerInstructions = setOf(mark - 1, mark, clear - 1, clear)
        assertEquals(original, marked.filterIndexed { index, _ -> index !in markerInstructions }.map { it.opcode })
        val parseGuard = marked[mark - 2] as BuilderOffsetInstruction
        val inputNull = marked[if (sharedReturn) 0 else 1] as BuilderOffsetInstruction
        val nullIndex = parseGuard.target.location.index
        assertEquals(nullIndex, inputNull.target.location.index)
        assertEquals(0, (marked[nullIndex] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals(Opcode.RETURN_OBJECT, marked[nullIndex + 1].opcode)
    }
}
