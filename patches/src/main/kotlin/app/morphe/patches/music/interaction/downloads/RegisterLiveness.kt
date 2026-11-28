/*
 * Copyright (C) 2026 lavinhoque33
 * Licensed under the GNU General Public License v3.0.
 * Written by lavinhoque33, 2026-11-27.
 */
package app.morphe.patches.music.interaction.downloads

import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ExceptionHandler
import com.android.tools.smali.dexlib2.iface.TryBlock
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction

/**
 * Whether [register] holds nothing the stock code needs when execution reaches [start]: on every path from there,
 * the stock code assigns it before anything reads it. A splice at [start] may borrow such a register, as long as the
 * splice leaves the register with a value of a type the code after it can take.
 *
 * Branches, switches and the handlers of [tryBlocks] are all followed. The proof errs on the side of "alive": a wide
 * operand counts as reading both of its registers, and a handler is entered as if the instruction that may throw had
 * not assigned the register yet.
 */
internal fun List<Instruction>.isRegisterDeadAt(
    start: Int,
    register: Int,
    tryBlocks: List<TryBlock<out ExceptionHandler>> = emptyList(),
): Boolean {
    val addresses = codeAddresses()
    val indexAt = addresses.withIndex().associate { (index, address) -> address to index }
    val pending = ArrayDeque(listOf(start))
    val visited = mutableSetOf<Int>()

    while (pending.isNotEmpty()) {
        val index = pending.removeLast()
        if (!visited.add(index)) continue

        val step = this[index]
        if (step.readsRegister(register)) return false

        pending += handlersOf(index, addresses, indexAt, tryBlocks)
        if (step.assignsRegister(register)) continue

        pending += successorsOf(index, addresses, indexAt)
    }
    return true
}

/** The address of each instruction in 16-bit code units, payloads included. */
private fun List<Instruction>.codeAddresses(): IntArray {
    val addresses = IntArray(size)
    var address = 0
    for (index in indices) {
        addresses[index] = address
        address += this[index].codeUnits
    }
    return addresses
}

private fun List<Instruction>.indexOfAddress(address: Int, indexAt: Map<Int, Int>): Int =
    checkNotNull(indexAt[address]) { "no instruction at code address $address" }

private fun List<Instruction>.handlersOf(
    index: Int,
    addresses: IntArray,
    indexAt: Map<Int, Int>,
    tryBlocks: List<TryBlock<out ExceptionHandler>>,
): List<Int> {
    if (!this[index].opcode.canThrow()) return emptyList()

    val address = addresses[index]
    return tryBlocks
        .filter { address >= it.startCodeAddress && address < it.startCodeAddress + it.codeUnitCount }
        .flatMap { it.exceptionHandlers }
        .map { indexOfAddress(it.handlerCodeAddress, indexAt) }
}

private fun List<Instruction>.successorsOf(index: Int, addresses: IntArray, indexAt: Map<Int, Int>): List<Int> {
    val step = this[index]
    val opcode = step.opcode
    val next = index + 1
    fun target(offset: Int) = indexOfAddress(addresses[index] + offset, indexAt)

    return when {
        opcode.name.startsWith("goto") -> listOf(target((step as OffsetInstruction).codeOffset))
        opcode.name.startsWith("if-") -> listOf(target((step as OffsetInstruction).codeOffset), next)
        opcode == Opcode.PACKED_SWITCH || opcode == Opcode.SPARSE_SWITCH -> {
            val payload = this[target((step as OffsetInstruction).codeOffset)] as SwitchPayload
            payload.switchElements.map { target(it.offset) } + next
        }
        opcode.canContinue() -> listOf(next)
        else -> emptyList()
    }
}

private fun Instruction.assignsRegister(register: Int): Boolean {
    val destination = this as? OneRegisterInstruction ?: return false
    return (opcode.setsRegister() && destination.registerA == register) ||
        (opcode.setsWideRegister() && (destination.registerA == register || destination.registerA + 1 == register))
}

private fun Instruction.readsRegister(register: Int): Boolean = register in readRegisters()

/** Operands the instruction reads, which are all its operands but a destination that is only written. */
private fun Instruction.readRegisters(): List<Int> {
    val sources = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is ThreeRegisterInstruction -> listOf(registerB, registerC) + firstOperandIfRead()
        is TwoRegisterInstruction -> listOf(registerB) + firstOperandIfRead()
        is OneRegisterInstruction -> firstOperandIfRead()
        else -> emptyList()
    }
    return if (opcode.isWide()) sources.flatMap { listOf(it, it + 1) } else sources
}

private fun Instruction.firstOperandIfRead(): List<Int> {
    val first = (this as OneRegisterInstruction).registerA
    val destinationOnly = (opcode.setsRegister() || opcode.setsWideRegister()) &&
        opcode != Opcode.CHECK_CAST && !opcode.name.endsWith("/2addr")
    return if (destinationOnly) emptyList() else listOf(first)
}

/** Opcodes that may take a register pair. Counting the second register of every such operand is deliberate. */
private fun Opcode.isWide(): Boolean = name.contains("wide") || name.contains("long") || name.contains("double")
