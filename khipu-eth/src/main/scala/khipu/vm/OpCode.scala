package khipu.vm

import akka.util.ByteString
import khipu.DataWord
import khipu.crypto
import khipu.domain.Address
import khipu.domain.TxLogEntry
import scala.collection.mutable

object OpCodes {

  val LogOpCodes: List[OpCode[_]] = List(
    LOG0,
    LOG1,
    LOG2,
    LOG3,
    LOG4
  )

  val SwapOpCodes: List[OpCode[_]] = List(
    SWAP1,
    SWAP2,
    SWAP3,
    SWAP4,
    SWAP5,
    SWAP6,
    SWAP7,
    SWAP8,
    SWAP9,
    SWAP10,
    SWAP11,
    SWAP12,
    SWAP13,
    SWAP14,
    SWAP15,
    SWAP16
  )

  val DupOpCodes: List[OpCode[_]] = List(
    DUP1,
    DUP2,
    DUP3,
    DUP4,
    DUP5,
    DUP6,
    DUP7,
    DUP8,
    DUP9,
    DUP10,
    DUP11,
    DUP12,
    DUP13,
    DUP14,
    DUP15,
    DUP16
  )

  val PushOpCodes: List[OpCode[_]] = List(
    PUSH1,
    PUSH2,
    PUSH3,
    PUSH4,
    PUSH5,
    PUSH6,
    PUSH7,
    PUSH8,
    PUSH9,
    PUSH10,
    PUSH11,
    PUSH12,
    PUSH13,
    PUSH14,
    PUSH15,
    PUSH16,
    PUSH17,
    PUSH18,
    PUSH19,
    PUSH20,
    PUSH21,
    PUSH22,
    PUSH23,
    PUSH24,
    PUSH25,
    PUSH26,
    PUSH27,
    PUSH28,
    PUSH29,
    PUSH30,
    PUSH31,
    PUSH32
  )

  val FrontierOpCodes: List[OpCode[_]] = LogOpCodes ++ SwapOpCodes ++ PushOpCodes ++ DupOpCodes ++ List(
    STOP,
    ADD,
    MUL,
    SUB,
    DIV,
    SDIV,
    MOD,
    SMOD,
    ADDMOD,
    MULMOD,
    EXP,
    SIGNEXTEND,

    LT,
    GT,
    SLT,
    SGT,
    EQ,
    ISZERO,
    AND,
    OR,
    XOR,
    NOT,
    BYTE,

    SHA3,

    ADDRESS,
    BALANCE,
    ORIGIN,
    CALLER,
    CALLVALUE,
    CALLDATALOAD,
    CALLDATASIZE,
    CALLDATACOPY,
    CODESIZE,
    CODECOPY,
    GASPRICE,
    EXTCODESIZE,
    EXTCODECOPY,

    BLOCKHASH,
    COINBASE,
    TIMESTAMP,
    NUMBER,
    DIFFICULTY,
    GASLIMIT,

    POP,
    MLOAD,
    MSTORE,
    MSTORE8,
    SLOAD,
    SSTORE,
    JUMP,
    JUMPI,
    PC,
    MSIZE,
    GAS,
    JUMPDEST,

    CREATE,
    CALL,
    CALLCODE,
    RETURN,
    INVALID,
    SELFDESTRUCT
  )

  val HomesteadOpCodes: List[OpCode[_]] = FrontierOpCodes ++ List(
    DELEGATECALL
  )

  val ByzantiumOpCodes: List[OpCode[_]] = HomesteadOpCodes ++ List(
    REVERT,
    RETURNDATASIZE,
    RETURNDATACOPY,
    STATICCALL
  )

  val ConstantinopleCodes: List[OpCode[_]] = ByzantiumOpCodes ++ List(
    SHL,
    SHR,
    SAR,
    CREATE2,
    EXTCODEHASH
  )

  val IstanbulCodes: List[OpCode[_]] = ConstantinopleCodes ++ List(
    SELFBALANCE,
    CHAINID
  )
}

object OpCode {
  def sliceBytes(bytes: ByteString, offset: Int, size: Int): ByteString = sliceBytes(bytes.toArray, offset, size)
  def sliceBytes(bytes: Array[Byte], offset: Int, size: Int): ByteString = {
    if (offset >= 0 && offset <= Int.MaxValue && size > 0) {
      val slice = Array.ofDim[Byte](size) // auto filled with 0
      if (offset < bytes.length) {
        System.arraycopy(bytes, offset, slice, 0, math.min(size, bytes.length - offset))
      }
      ByteString(slice)
    } else {
      ByteString()
    }
  }
}

/**
 * Base class for all the opcodes of the EVM
 *
 * @tparam type of params
 * @param code Opcode byte representation
 * @param delta number of words to be popped from stack
 * @param alpha number of words to be pushed to stack
 */
sealed abstract class OpCode[P](val code: Byte, val delta: Int, val alpha: Int) {
  def this(code: Int, pop: Int, push: Int) = this(code.toByte, pop, push)

  final def execute[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]): ProgramState[W, S] = {
    if (state.stack.size < delta) {
      state.withError(StackUnderflow)
    } else if (state.stack.size - delta + alpha > state.stack.maxSize) {
      state.withError(StackOverflow)
    } else {
      val params = getParams(state)
      if (state.error.isEmpty) { // error is checked during getParams
        val baseGas = constGas(state.config.feeSchedule)
        val moreGas = variableGas(state, params)
        val spendingGas = baseGas + moreGas
        //println(s"spendingGas: $spendingGas, state.gas ${state.gas}, OOG? ${spendingGas > state.gas}")
        // TODO since we use Long (signed number type) to calculate gas, how to 
        // make sure the value is always > 0 and < Long.MaxValue to avoid it becoming 
        // negative value
        if (moreGas < 0 || spendingGas < 0 || spendingGas > state.gas) {
          state.withGas(0).withError(OutOfGas)
        } else {
          exec(state, params).spendGas(spendingGas)
        }
      } else {
        state
      }
    }
  }

  protected def constGas(s: FeeSchedule): Long
  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: P): Long
  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: P): ProgramState[W, S]
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]): P
}

sealed trait ConstGas[P] { self: OpCode[P] =>
  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: P): Long = 0
}

case object STOP extends OpCode[Unit](0x00, 0, 0) with ConstGas[Unit] {
  protected def constGas(s: FeeSchedule) = s.G_zero
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] =
    state.halt
}

sealed abstract class BinaryOp(code: Int) extends OpCode[(DataWord, DataWord)](code, 2, 1) {
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(a, b) = state.stack.pop(2)
    (a, b)
  }

  final protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): ProgramState[W, S] = {
    val (a, b) = params
    val res = f(a, b)
    state.stack.push(res)
    state.step()
  }

  protected def f(x: DataWord, y: DataWord): DataWord
}
case object ADD extends BinaryOp(0x01) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = x + y
}
case object MUL extends BinaryOp(0x02) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_low
  protected def f(x: DataWord, y: DataWord) = x * y
}
case object SUB extends BinaryOp(0x03) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = x - y
}
case object DIV extends BinaryOp(0x04) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_low
  protected def f(x: DataWord, y: DataWord) = x div y
}
case object SDIV extends BinaryOp(0x05) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_low
  protected def f(x: DataWord, y: DataWord) = x sdiv y
}
case object MOD extends BinaryOp(0x06) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_low
  protected def f(x: DataWord, y: DataWord) = x mod y
}
case object SMOD extends BinaryOp(0x07) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_low
  protected def f(x: DataWord, y: DataWord) = x smod y
}
case object EXP extends BinaryOp(0x0a) {
  protected def constGas(s: FeeSchedule) = s.G_exp
  protected def f(x: DataWord, y: DataWord) = x ** y

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): Long = {
    val (_, m) = params
    state.config.feeSchedule.G_expbyte * m.byteSize
  }
}
case object SIGNEXTEND extends BinaryOp(0x0b) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_low
  protected def f(x: DataWord, y: DataWord) = y signExtend x
}
case object LT extends BinaryOp(0x10) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = DataWord(x < y)
}
case object GT extends BinaryOp(0x11) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = DataWord(x > y)
}
case object SLT extends BinaryOp(0x12) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = DataWord(x slt y)
}
case object SGT extends BinaryOp(0x13) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = DataWord(x sgt y)
}
case object EQ extends BinaryOp(0x14) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = DataWord(x.n.compareTo(y.n) == 0)
}
case object AND extends BinaryOp(0x16) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = x & y
}
case object OR extends BinaryOp(0x17) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = x | y
}
case object XOR extends BinaryOp(0x18) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = x ^ y
}
case object BYTE extends BinaryOp(0x1a) with ConstGas[(DataWord, DataWord)] {
  override protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(a, b) = state.stack.pop(2)
    if (a.compareTo(DataWord.MaxInt) > 0) {
      state.withError(ArithmeticException)
    }
    (a, b)
  }

  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = y getByte x
}
case object SHL extends BinaryOp(0x1b) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = y shiftLeft x
}
case object SHR extends BinaryOp(0x1c) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = y shiftRight x
}
case object SAR extends BinaryOp(0x1d) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord, y: DataWord) = y shiftRightSigned x
}

sealed abstract class UnaryOp(code: Int) extends OpCode[DataWord](code, 1, 1) with ConstGas[DataWord] {
  final protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(a) = state.stack.pop()
    a
  }

  final protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): ProgramState[W, S] = {
    val a = params
    val res = f(a)
    state.stack.push(res)
    state.step()
  }

  protected def f(x: DataWord): DataWord
}
case object ISZERO extends UnaryOp(0x15) {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord) = DataWord(x.isZero)
}
case object NOT extends UnaryOp(0x19) {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def f(x: DataWord) = ~x
}

sealed abstract class TernaryOp(code: Int) extends OpCode[(DataWord, DataWord, DataWord)](code, 3, 1) {
  final protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(a, b, c) = state.stack.pop(3)
    (a, b, c)
  }

  final protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord)): ProgramState[W, S] = {
    val (a, b, c) = params
    val res = f(a, b, c)
    state.stack.push(res)
    state.step()
  }

  protected def f(x: DataWord, y: DataWord, z: DataWord): DataWord
}
case object ADDMOD extends TernaryOp(0x08) with ConstGas[(DataWord, DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_mid
  protected def f(x: DataWord, y: DataWord, z: DataWord) = x.addmod(y, z)
}
case object MULMOD extends TernaryOp(0x09) with ConstGas[(DataWord, DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_mid
  protected def f(x: DataWord, y: DataWord, z: DataWord) = x.mulmod(y, z)
}

case object SHA3 extends OpCode[(DataWord, DataWord)](0x20, 2, 1) {
  protected def constGas(s: FeeSchedule) = s.G_sha3

  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    // do not need to check params bounds, just use safe int value
    val List(offset, size) = state.stack.pop(2)
    (DataWord.safe(offset.intValueSafe), DataWord.safe(size.intValueSafe))
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): ProgramState[W, S] = {
    val (offset, size) = params
    val input = state.memory.load(offset.intValueSafe, size.intValueSafe)
    val hash = crypto.kec256(input.toArray)
    state.stack.push(DataWord(hash))
    state.step()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): Long = {
    val (offset, size) = params
    val memCost = state.config.calcMemCost(state.memory.size, offset.longValueSafe, size.longValueSafe)
    val shaCost = state.config.feeSchedule.G_sha3word * DataWord.wordsForBytes(size.longValueSafe)
    memCost + shaCost
  }
}

sealed abstract class ConstOp(code: Int) extends OpCode[Unit](code, 0, 1) with ConstGas[Unit] {
  final protected def constGas(s: FeeSchedule) = s.G_base
  final protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = Nil

  final protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] = {
    state.stack.push(f(state))
    state.step()
  }

  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]): DataWord
}
case object ADDRESS extends ConstOp(0x30) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = s.env.ownerAddr.toDataWord
}
case object ORIGIN extends ConstOp(0x32) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = s.env.originAddr.toDataWord
}
case object CALLER extends ConstOp(0x33) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = s.env.callerAddr.toDataWord
}
case object CALLVALUE extends ConstOp(0x34) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = s.env.value
}
case object CALLDATASIZE extends ConstOp(0x36) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = DataWord.safe(s.input.size)
}
case object GASPRICE extends ConstOp(0x3a) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = s.env.gasPrice
}
case object CODESIZE extends ConstOp(0x38) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = DataWord.safe(s.env.program.length)
}
case object COINBASE extends ConstOp(0x41) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = DataWord(s.env.blockHeader.beneficiary)
}
case object TIMESTAMP extends ConstOp(0x42) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = DataWord(s.env.blockHeader.unixTimestamp)
}
case object NUMBER extends ConstOp(0x43) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = DataWord(s.env.blockHeader.number)
}
case object DIFFICULTY extends ConstOp(0x44) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = s.env.blockHeader.difficulty
}
case object GASLIMIT extends ConstOp(0x45) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = DataWord(s.env.blockHeader.gasLimit)
}
case object PC extends ConstOp(0x58) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = DataWord.safe(s.pc)
}
case object MSIZE extends ConstOp(0x59) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = DataWord(DataWord.SIZE * DataWord.wordsForBytes(s.memory.size))
}
case object GAS extends ConstOp(0x5a) {
  protected def f(s: ProgramState[_ <: WorldState[_, _ <: Storage[_]], _ <: Storage[_]]) = DataWord(s.gas - s.config.feeSchedule.G_base)
}

case object BALANCE extends OpCode[DataWord](0x31, 1, 1) with ConstGas[DataWord] {
  protected def constGas(s: FeeSchedule) = s.G_balance
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(accountAddress) = state.stack.pop()
    accountAddress
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): ProgramState[W, S] = {
    val accountAddress = params
    val accountBalance = state.world.getBalance(Address(accountAddress))
    state.stack.push(accountBalance)
    state.withParallelRaceCondition(ProgramState.OnAccount).step()
  }
}

case object CALLDATALOAD extends OpCode[Int](0x35, 1, 1) with ConstGas[Int] {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    // do not need to check params bound, just use int value, possible overflow is processed in sliceBytes
    val List(offset) = state.stack.pop()
    offset.intValueSafe // Note: ethereumj seems use offset.n.intValue here, it won't work on Tx 0x3d956f1ae474bb1d2d5147332e052912a4b94a956fee945e7a5074e5657459f9 
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Int): ProgramState[W, S] = {
    val offset = params
    val data = OpCode.sliceBytes(state.input, offset, 32)
    state.stack.push(DataWord(data))
    state.step()
  }
}

case object CALLDATACOPY extends OpCode[(DataWord, DataWord, DataWord)](0x37, 3, 0) {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    // do not need to check params bound, just use save int value
    val List(memOffset, dataOffset, size) = state.stack.pop(3)
    (memOffset, dataOffset, size)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord)): ProgramState[W, S] = {
    val (memOffset, dataOffset, size) = params
    val data = OpCode.sliceBytes(state.input, dataOffset.intValueSafe, size.intValueSafe)
    state.memory.store(memOffset.intValueSafe, data)
    state.step()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord)): Long = {
    val (memOffset, _, size) = params
    val memCost = state.config.calcMemCost(state.memory.size, memOffset.longValueSafe, size.longValueSafe)
    val copyCost = state.config.feeSchedule.G_copy * DataWord.wordsForBytes(size.longValueSafe)
    memCost + copyCost
  }
}

case object CODECOPY extends OpCode[(DataWord, DataWord, DataWord)](0x39, 3, 0) {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    // do not need to check params bound, just use safe int value
    val List(memOffset, codeOffset, size) = state.stack.pop(3)
    (memOffset, codeOffset, size)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord)): ProgramState[W, S] = {
    val (memOffset, codeOffset, size) = params
    val bytes = OpCode.sliceBytes(state.program.code, codeOffset.intValueSafe, size.intValueSafe)
    state.memory.store(memOffset.intValueSafe, bytes)
    state.step()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord)): Long = {
    val (memOffset, _, size) = params
    val memCost = state.config.calcMemCost(state.memory.size, memOffset.longValueSafe, size.longValueSafe)
    val copyCost = state.config.feeSchedule.G_copy * DataWord.wordsForBytes(size.longValueSafe)
    memCost + copyCost
  }
}

case object EXTCODESIZE extends OpCode[DataWord](0x3b, 1, 1) with ConstGas[DataWord] {
  protected def constGas(s: FeeSchedule) = s.G_extcodesize
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(addr) = state.stack.pop()
    addr
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): ProgramState[W, S] = {
    val addr = params
    val codeSize = state.world.getCode(Address(addr)).size
    state.stack.push(DataWord(codeSize))
    state.step()
  }
}

case object EXTCODECOPY extends OpCode[(DataWord, DataWord, DataWord, DataWord)](0x3c, 4, 0) {
  protected def constGas(s: FeeSchedule) = s.G_extcodecopy
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    // do not need to check params bound, just use safe int value
    val List(address, memOffset, codeOffset, size) = state.stack.pop(4)
    (address, DataWord.safe(memOffset.intValueSafe), DataWord.safe(codeOffset.intValueSafe), DataWord.safe(size.intValueSafe))
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord, DataWord)): ProgramState[W, S] = {
    val (address, memOffset, codeOffset, size) = params
    val codeCopy = OpCode.sliceBytes(state.world.getCode(Address(address)), codeOffset.intValueSafe, size.intValueSafe)
    state.memory.store(memOffset.intValueSafe, codeCopy)
    state.step()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord, DataWord)): Long = {
    val (_, memOffset, _, size) = params
    val memCost = state.config.calcMemCost(state.memory.size, memOffset.longValueSafe, size.longValueSafe)
    val copyCost = state.config.feeSchedule.G_copy * DataWord.wordsForBytes(size.longValueSafe)
    memCost + copyCost
  }
}

case object EXTCODEHASH extends OpCode[DataWord](0x3f, 1, 1) with ConstGas[DataWord] {
  protected def constGas(s: FeeSchedule) = s.G_extcodehash
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(addr) = state.stack.pop()
    addr
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): ProgramState[W, S] = {
    val addr = params
    val codeHash = state.world.getCodeHash(Address(addr)).getOrElse(DataWord.Zero)
    state.stack.push(codeHash)
    state.step()
  }
}

case object RETURNDATASIZE extends OpCode[Unit](0x3d, 0, 1) with ConstGas[Unit] {
  protected def constGas(s: FeeSchedule) = s.G_base
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] = {
    val dataSize = state.returnDataBuffer.length
    state.stack.push(DataWord(dataSize))
    state.step()
  }
}

case object RETURNDATACOPY extends OpCode[(DataWord, DataWord, DataWord)](0x3e, 3, 0) {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    // do not need to check params bound, just use safe int value
    val List(memOffset, dataOffset, size) = state.stack.pop(3)
    (memOffset, dataOffset, size)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord)): ProgramState[W, S] = {
    val (memOffset, dataOffset, size) = params
    val data = OpCode.sliceBytes(state.returnDataBuffer, dataOffset.intValueSafe, size.intValueSafe)
    state.memory.store(memOffset.intValueSafe, data)
    state.step()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord)): Long = {
    val (memOffset, _, size) = params
    val memCost = state.config.calcMemCost(state.memory.size, memOffset.longValueSafe, size.longValueSafe)
    val copyCost = state.config.feeSchedule.G_copy * DataWord.wordsForBytes(size.longValueSafe)
    memCost + copyCost
  }
}

case object BLOCKHASH extends OpCode[Int](0x40, 1, 1) with ConstGas[Int] {
  protected def constGas(s: FeeSchedule) = s.G_blockhash
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(blockNumber) = state.stack.pop()
    blockNumber.intValueSafe
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Int): ProgramState[W, S] = {
    val blockNumber = params
    val outOfLimits = state.env.blockHeader.number - blockNumber > 256 || blockNumber >= state.env.blockHeader.number
    val hash = if (outOfLimits) DataWord.Zero else state.world.getBlockHash(blockNumber).getOrElse(DataWord.Zero)
    state.stack.push(hash)
    state.step()
  }
}

case object CHAINID extends OpCode[Unit](0x46, 0, 1) with ConstGas[Unit] {
  protected def constGas(s: FeeSchedule) = s.G_base
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] = {
    val chainId = state.config.chainId
    state.stack.push(chainId)
    state.step()
  }
}

case object SELFBALANCE extends OpCode[Unit](0x47, 0, 1) with ConstGas[Unit] {
  protected def constGas(s: FeeSchedule) = s.G_low
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] = {
    val balance = state.ownBalance
    state.stack.push(balance)
    state.step()
  }
}

case object POP extends OpCode[Unit](0x50, 1, 0) with ConstGas[Unit] {
  protected def constGas(s: FeeSchedule) = s.G_base
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] = {
    state.stack.pop()
    state.step()
  }
}

case object MLOAD extends OpCode[DataWord](0x51, 1, 1) {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(offset) = state.stack.pop()
    if (offset.compareTo(DataWord.MaxInt) > 0) {
      state.withError(ArithmeticException) // why MLOAD/MSTORE requires bounded offset
    }
    offset
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): ProgramState[W, S] = {
    val offset = params
    val word = state.memory.load(offset.intValueSafe)
    state.stack.push(word)
    state.step()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): Long = {
    val offset = params
    state.config.calcMemCost(state.memory.size, offset.longValueSafe, DataWord.SIZE)
  }
}

case object MSTORE extends OpCode[(DataWord, DataWord)](0x52, 2, 0) {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(offset, value) = state.stack.pop(2)
    if (offset.compareTo(DataWord.MaxInt) > 0) {
      state.withError(ArithmeticException)
    }
    (offset, value)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): ProgramState[W, S] = {
    val (offset, value) = params
    state.memory.store(offset.intValueSafe, value)
    state.step()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): Long = {
    val (offset, _) = params
    state.config.calcMemCost(state.memory.size, offset.longValueSafe, DataWord.SIZE)
  }
}

case object MSTORE8 extends OpCode[(DataWord, DataWord)](0x53, 2, 0) {
  protected def constGas(s: FeeSchedule) = s.G_verylow
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    // do not need to check params bound, just use safe int value
    val List(offset, value) = state.stack.pop(2)
    (offset, value)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): ProgramState[W, S] = {
    val (offset, value) = params
    val valueToByte = (value mod DataWord.TwoFiveSix).n.byteValue
    state.memory.store(offset.intValueSafe, valueToByte)
    state.step()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): Long = {
    val (offset, _) = params
    state.config.calcMemCost(state.memory.size, offset.longValueSafe, 1)
  }
}

case object SLOAD extends OpCode[DataWord](0x54, 1, 1) with ConstGas[DataWord] {
  protected def constGas(s: FeeSchedule) = s.G_sload
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(offset) = state.stack.pop()
    offset
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): ProgramState[W, S] = {
    val offset = params
    val value = state.storage.load(offset)
    state.stack.push(value)
    state.step()
  }
}

case object SSTORE extends OpCode[(DataWord, DataWord)](0x55, 2, 0) {
  protected def constGas(s: FeeSchedule) = s.G_zero
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(offset, value) = state.stack.pop(2)
    (offset, value)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): ProgramState[W, S] = {
    if (state.context.isStaticCall) {
      state.withError(StaticCallModification)
    } else {
      val (offset, newValue) = params

      val refund = refundGas(state, params) // must calc before storage.store(key, newValue) to keep currValue
      val updatedStorage = state.storage.store(offset, newValue)
      val world = state.world.saveStorage(state.ownAddress, updatedStorage)

      state
        .withWorld(world)
        .refundGas(refund)
        .step()
    }
  }

  private def getOriginalValue[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], offset: DataWord) = {
    state.context.originalStorageValues.get(state.ownAddress) match {
      case Some(map) =>
        map.get(offset) match {
          case Some(ori) => ori
          case None =>
            val ori = state.storage.load(offset)
            map += (offset -> ori)
            ori
        }
      case None =>
        val map = new mutable.HashMap[DataWord, DataWord]()
        state.context.originalStorageValues += (state.ownAddress -> map)
        val ori = state.storage.load(offset)
        map += (offset -> ori)
        ori
    }
  }

  private def refundGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): Long = {
    val (offset, newValue) = params
    val currValue = state.storage.load(offset)
    if (state.config.eip2200) { // https://eips.ethereum.org/EIPS/eip-2200
      if (currValue == newValue) {
        0L
      } else { // currValue != newValue
        val origValue = getOriginalValue(state, offset)
        if (currValue == origValue) {
          if (origValue.isZero) {
            0L
          } else if (newValue.isZero) {
            state.config.feeSchedule.R_sclear
          } else {
            0L
          }
        } else { // currValue != origValue and currValue != newValue
          var _refund = 0L
          if (origValue.nonZero) {
            if (currValue.isZero) {
              _refund -= state.config.feeSchedule.R_sclear
            } else if (newValue.isZero) {
              _refund += state.config.feeSchedule.R_sclear
            }
          }

          if (origValue == newValue) { // this storage slot is reset
            if (origValue.isZero) {
              _refund += (state.config.feeSchedule.G_sset - state.config.feeSchedule.G_sload)
            } else {
              _refund += (state.config.feeSchedule.G_sreset - state.config.feeSchedule.G_sload)
            }
          }
          _refund
        }
      }
    } else {
      if (currValue.nonZero && newValue.isZero) {
        state.config.feeSchedule.R_sclear
      } else {
        0L
      }
    }
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): Long = {
    if (state.config.eip2200 && state.gas <= state.config.feeSchedule.G_ssentry) {
      -1 // OutOfGas
    } else {
      val (offset, newValue) = params
      val currValue = state.storage.load(offset)
      if (state.config.eip2200) { // https://eips.ethereum.org/EIPS/eip-2200
        if (currValue == newValue) {
          state.config.feeSchedule.G_sload
        } else { // currValue != newValue
          val origValue = getOriginalValue(state, offset)
          if (currValue == origValue) { // this storage slot has not been changed by the current execution context
            if (origValue.isZero) {
              state.config.feeSchedule.G_sset
            } else {
              state.config.feeSchedule.G_sreset
            }
          } else { // currValue != origValue and currValue != newValue, this storage slot is dirty
            state.config.feeSchedule.G_sload
          }
        }
      } else {
        if (currValue.isZero && newValue.nonZero) {
          state.config.feeSchedule.G_sset
        } else {
          state.config.feeSchedule.G_sreset
        }
      }
    }
  }
}

case object JUMP extends OpCode[DataWord](0x56, 1, 0) with ConstGas[DataWord] {
  protected def constGas(s: FeeSchedule) = s.G_mid
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(pos) = state.stack.pop()
    pos
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): ProgramState[W, S] = {
    val pos = params
    val dest = pos.toInt // fail with InvalidJump if convertion to Int is lossy

    if (pos == dest && state.program.isValidJumpDestination(dest)) {
      state.goto(dest)
    } else {
      state.withError(InvalidJump(pos))
    }
  }
}

case object JUMPI extends OpCode[(DataWord, DataWord)](0x57, 2, 0) with ConstGas[(DataWord, DataWord)] {
  protected def constGas(s: FeeSchedule) = s.G_high
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(pos, cond) = state.stack.pop(2)
    (pos, cond)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): ProgramState[W, S] = {
    val (pos, cond) = params
    val dest = pos.toInt // fail with InvalidJump if convertion to Int is lossy

    if (cond.isZero) {
      state.step()
    } else if (pos == dest && state.program.isValidJumpDestination(dest)) {
      state.goto(dest)
    } else {
      state.withError(InvalidJump(pos))
    }
  }
}

case object JUMPDEST extends OpCode[Unit](0x5b, 0, 0) with ConstGas[Unit] {
  protected def constGas(s: FeeSchedule) = s.G_jumpdest
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] = {
    state.step()
  }
}

sealed abstract class PushOp private (code: Int, val i: Int) extends OpCode[Unit](code, 0, 1) with ConstGas[Unit] {
  def this(code: Int) = this(code, code - 0x60)

  final protected def constGas(s: FeeSchedule) = s.G_verylow
  final protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  final protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] = {
    val n = i + 1
    val bytes = state.program.getBytes(state.pc + 1, n)
    state.stack.push(DataWord(bytes))
    state.step(n + 1)
  }
}
case object PUSH1 extends PushOp(0x60)
case object PUSH2 extends PushOp(0x61)
case object PUSH3 extends PushOp(0x62)
case object PUSH4 extends PushOp(0x63)
case object PUSH5 extends PushOp(0x64)
case object PUSH6 extends PushOp(0x65)
case object PUSH7 extends PushOp(0x66)
case object PUSH8 extends PushOp(0x67)
case object PUSH9 extends PushOp(0x68)
case object PUSH10 extends PushOp(0x69)
case object PUSH11 extends PushOp(0x6a)
case object PUSH12 extends PushOp(0x6b)
case object PUSH13 extends PushOp(0x6c)
case object PUSH14 extends PushOp(0x6d)
case object PUSH15 extends PushOp(0x6e)
case object PUSH16 extends PushOp(0x6f)
case object PUSH17 extends PushOp(0x70)
case object PUSH18 extends PushOp(0x71)
case object PUSH19 extends PushOp(0x72)
case object PUSH20 extends PushOp(0x73)
case object PUSH21 extends PushOp(0x74)
case object PUSH22 extends PushOp(0x75)
case object PUSH23 extends PushOp(0x76)
case object PUSH24 extends PushOp(0x77)
case object PUSH25 extends PushOp(0x78)
case object PUSH26 extends PushOp(0x79)
case object PUSH27 extends PushOp(0x7a)
case object PUSH28 extends PushOp(0x7b)
case object PUSH29 extends PushOp(0x7c)
case object PUSH30 extends PushOp(0x7d)
case object PUSH31 extends PushOp(0x7e)
case object PUSH32 extends PushOp(0x7f)

sealed abstract class DupOp private (code: Int, val i: Int) extends OpCode[Unit](code, i + 1, i + 2) with ConstGas[Unit] {
  def this(code: Int) = this(code, code - 0x80)

  final protected def constGas(s: FeeSchedule) = s.G_verylow
  final protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  final protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] = {
    state.stack.dup(i)
    state.step()
  }
}
case object DUP1 extends DupOp(0x80)
case object DUP2 extends DupOp(0x81)
case object DUP3 extends DupOp(0x82)
case object DUP4 extends DupOp(0x83)
case object DUP5 extends DupOp(0x84)
case object DUP6 extends DupOp(0x85)
case object DUP7 extends DupOp(0x86)
case object DUP8 extends DupOp(0x87)
case object DUP9 extends DupOp(0x88)
case object DUP10 extends DupOp(0x89)
case object DUP11 extends DupOp(0x8a)
case object DUP12 extends DupOp(0x8b)
case object DUP13 extends DupOp(0x8c)
case object DUP14 extends DupOp(0x8d)
case object DUP15 extends DupOp(0x8e)
case object DUP16 extends DupOp(0x8f)

sealed abstract class SwapOp private (code: Int, val i: Int) extends OpCode[Unit](code, i + 2, i + 2) with ConstGas[Unit] {
  def this(code: Int) = this(code, code - 0x90)

  final protected def constGas(s: FeeSchedule) = s.G_verylow
  final protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  final protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] = {
    state.stack.swap(i + 1)
    state.step()
  }
}
case object SWAP1 extends SwapOp(0x90)
case object SWAP2 extends SwapOp(0x91)
case object SWAP3 extends SwapOp(0x92)
case object SWAP4 extends SwapOp(0x93)
case object SWAP5 extends SwapOp(0x94)
case object SWAP6 extends SwapOp(0x95)
case object SWAP7 extends SwapOp(0x96)
case object SWAP8 extends SwapOp(0x97)
case object SWAP9 extends SwapOp(0x98)
case object SWAP10 extends SwapOp(0x99)
case object SWAP11 extends SwapOp(0x9a)
case object SWAP12 extends SwapOp(0x9b)
case object SWAP13 extends SwapOp(0x9c)
case object SWAP14 extends SwapOp(0x9d)
case object SWAP15 extends SwapOp(0x9e)
case object SWAP16 extends SwapOp(0x9f)

sealed abstract class LogOp private (code: Int, val i: Int) extends OpCode[(DataWord, DataWord, List[DataWord])](code, i + 2, 0) {
  def this(code: Int) = this(code, code - 0xa0)

  final protected def constGas(s: FeeSchedule) = s.G_log
  final protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    // do not need to check params bound, just use save int value
    val offset :: size :: topics = state.stack.pop(delta)
    (offset, size, topics)
  }

  final protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, List[DataWord])): ProgramState[W, S] = {
    if (state.context.isStaticCall) {
      state.withError(StaticCallModification)
    } else {
      val (offset, size, topics) = params
      val data = state.memory.load(offset.intValueSafe, size.intValueSafe)
      val logEntry = TxLogEntry(state.env.ownerAddr, topics.map(x => ByteString(x.bytes)), data)
      state.withTxLog(logEntry).step()
    }
  }

  final protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, List[DataWord])): Long = {
    val (offset, size, _) = params
    val memCost = state.config.calcMemCost(state.memory.size, offset.longValueSafe, size.longValueSafe)
    val logCost = state.config.feeSchedule.G_logdata * size.toMaxLong + i * state.config.feeSchedule.G_logtopic
    memCost + logCost
  }
}
case object LOG0 extends LogOp(0xa0)
case object LOG1 extends LogOp(0xa1)
case object LOG2 extends LogOp(0xa2)
case object LOG3 extends LogOp(0xa3)
case object LOG4 extends LogOp(0xa4)

sealed abstract class CreatOp[P](code: Int, delta: Int, alpha: Int) extends OpCode[P](code.toByte, delta, alpha) {

  final protected def doExec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], endowment: DataWord, inOffset: DataWord, inSize: DataWord, salt: Array[Byte], params: P): ProgramState[W, S] = {
    if (state.context.isStaticCall) {
      state.withError(StaticCallModification)
    } else {
      state.resetReturnDataBuffer() // reset before call

      val isValidCall = state.env.callDepth < EvmConfig.MaxCallDepth && endowment <= state.ownBalance

      if (isValidCall) {

        //FIXME: to avoid calculating this twice, we could adjust state.gas prior to execution in OpCode#execute
        //not sure how this would affect other opcodes [EC-243]
        val availableGas = state.gas - (constGas(state.config.feeSchedule) + variableGas(state, params))
        val startGas = state.config.gasCap(availableGas)

        val initCode = state.memory.load(inOffset.intValueSafe, inSize.intValueSafe).toArray
        // if creation fails at this point we still leave the creators nonce incremented
        createContactAddress[W, S](state, initCode, salt) match {
          case Right((address, world)) =>
            val (newAddress, checkpoint, worldAtCheckpoint) = (address, world.copy, world)
            //println(s"newAddress: $newAddress via ${state.env.ownerAddr} in CREATE")
            val worldBeforeTransfer = if (state.config.eip161) {
              worldAtCheckpoint.increaseNonce(newAddress)
            } else {
              worldAtCheckpoint
            }

            val worldAfterTransfer = worldBeforeTransfer.transfer(state.env.ownerAddr, newAddress, endowment)

            val env = state.env.copy(
              callerAddr = state.env.ownerAddr,
              ownerAddr = newAddress,
              value = endowment,
              program = Program(initCode, state.config),
              input = ByteString(),
              callDepth = state.env.callDepth + 1
            )

            val context = ProgramContext[W, S](
              env,
              newAddress,
              startGas,
              worldAfterTransfer,
              state.config,
              state.addressesToDelete,
              state.addressesTouched + newAddress,
              state.context.isStaticCall,
              state.context.originalStorageValues
            )

            val result = VM.run(context, state.isDebugTraceEnabled)
            state.mergeParallelRaceConditions(result.parallelRaceConditions)

            if (result.isRevert) {
              state.withReturnDataBuffer(result.returnData)
            }

            val gasUsedInCreating = startGas - result.gasRemaining

            val code = result.returnData
            val codeDepositGas = state.config.calcCodeDepositCost(code)
            val isRequireGasForCodeDeposit = state.config.exceptionalFailedCodeDeposit && !result.isRevert
            val notEnoughGasForCodeDeposit = gasUsedInCreating + codeDepositGas > startGas

            val isCreationFailed = result.error.isDefined || (isRequireGasForCodeDeposit && notEnoughGasForCodeDeposit)

            if (isCreationFailed || result.isRevert) {
              state.stack.push(DataWord.Zero)

              if (result.error.isEmpty && result.isRevert) {
                state.spendGas(gasUsedInCreating)
              } else {
                state.spendGas(startGas)
              }

              // the error result may be caused by parallel race condition, so merge all possible modifies
              state
                .withParallelRaceCondition(ProgramState.OnError)
                .withWorld(checkpoint.mergeRaceConditions(result.world))
                .step()

            } else {
              state.stack.push(newAddress.toDataWord)

              state.spendGas(gasUsedInCreating)

              if (notEnoughGasForCodeDeposit) {
                state.withWorld(result.world)
              } else {
                if (code.length > state.config.maxContractSize) {
                  //println(s"Contract size too large: ${code.length}")
                  state.withWorld(result.world).withError(OutOfGas)
                } else {
                  if (!result.isRevert) {
                    val world3 = result.world.saveCode(newAddress, code)
                    state.withWorld(world3).spendGas(codeDepositGas)
                  }
                }
              }

              state
                .refundGas(result.gasRefund)
                .withAddAddressesToDelete(result.addressesToDelete)
                .withAddAddressesTouched(result.addressesTouched)
                .withTxLogs(result.txLogs)
                .step()
            }

          case Left(WorldState.AddressCollisions(address)) =>
            // throws immediately, with exactly the same behavior as would arise 
            // if the first byte in the init code were an invalid opcode.
            state.stack.push(DataWord.Zero)

            state
              .spendGas(startGas)
              .withInfo(s"Address collision at $address")
              .withParallelRaceCondition(ProgramState.OnAccount)
              .step()
        }
      } else { // invalid call
        state.stack.push(DataWord.Zero)

        if (endowment <= state.ownBalance) {
          state.withParallelRaceCondition(ProgramState.OnAccount)
        }
        state
          .step()
      }
    }
  }

  protected def createContactAddress[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], initCode: Array[Byte], salt: Array[Byte]): Either[WorldState.AddressCollisions, (Address, W)]
}
case object CREATE extends CreatOp[(DataWord, DataWord, DataWord)](0xf0, 3, 1) {
  protected def constGas(s: FeeSchedule) = s.G_create
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(endowment, inOffset, inSize) = state.stack.pop(3)
    if (inOffset.compareTo(DataWord.MaxInt) > 0 || inSize.compareTo(DataWord.MaxInt) > 0) {
      state.withError(ArithmeticException)
    }
    (endowment, inOffset, inSize)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord)): ProgramState[W, S] = {
    val (endowment, inOffset, inSize) = params
    doExec(state, endowment, inOffset, inSize, null, params)
  }

  protected def createContactAddress[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], initCode: Array[Byte], salt: Array[Byte]): Either[WorldState.AddressCollisions, (Address, W)] = {
    state.world.createContractAddress(state.env.ownerAddr)
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord)): Long = {
    val (_, inOffset, inSize) = params
    val memCost = state.config.calcMemCost(state.memory.size, inOffset.longValueSafe, inSize.longValueSafe)
    memCost
  }
}

case object CREATE2 extends CreatOp[(DataWord, DataWord, DataWord, DataWord)](0xf5, 4, 1) {
  protected def constGas(s: FeeSchedule) = s.G_create
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(endowment, inOffset, inSize, salt) = state.stack.pop(4)
    if (inOffset.compareTo(DataWord.MaxInt) > 0 || inSize.compareTo(DataWord.MaxInt) > 0) {
      state.withError(ArithmeticException)
    }
    (endowment, inOffset, inSize, salt)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord, DataWord)): ProgramState[W, S] = {
    val (endowment, inOffset, inSize, salt) = params
    doExec(state, endowment, inOffset, inSize, salt.bytes, params)
  }

  protected def createContactAddress[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], initCode: Array[Byte], salt: Array[Byte]): Either[WorldState.AddressCollisions, (Address, W)] = {
    state.world.createContractAddress(state.env.ownerAddr, initCode, salt)
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord, DataWord)): Long = {
    val (_, inOffset, inSize, _) = params
    val memCost = state.config.calcMemCost(state.memory.size, inOffset.longValueSafe, inSize.longValueSafe)
    val shaCost = state.config.feeSchedule.G_sha3word * DataWord.wordsForBytes(inSize.longValueSafe)
    memCost + shaCost
  }
}

sealed abstract class CallOp(code: Int, delta: Int, alpha: Int, hasValue: Boolean, isStateless: Boolean, isStatic: Boolean) extends OpCode[(DataWord, DataWord, DataWord, DataWord, DataWord, DataWord, DataWord)](code.toByte, delta, alpha) {
  final protected def constGas(s: FeeSchedule) = s.G_zero
  final protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(gas, target, callValue, inOffset, inSize, outOffset, outSize) = if (hasValue) {
      state.stack.pop(7)
    } else {
      val List(gas, target, inOffset, inSize, outOffset, outSize) = state.stack.pop(6)
      List(gas, target, DataWord.Zero, inOffset, inSize, outOffset, outSize)
    }
    if (inOffset.compareTo(DataWord.MaxInt) > 0 || inSize.compareTo(DataWord.MaxInt) > 0 || outOffset.compareTo(DataWord.MaxInt) > 0 || outOffset.compareTo(DataWord.MaxInt) > 0) {
      state.withError(ArithmeticException)
    }
    (gas, target, callValue, inOffset, inSize, outOffset, outSize)
  }

  /**
   * At block 2675119, shortly after the planned “Spurious Dragon” hard fork, see:
   *   https://github.com/ethereum/EIPs/issues/716   "Clarification about when touchedness is reverted during state clearance"
   *   https://github.com/ethereum/go-ethereum/pull/3341/files#r89547994
   */
  final protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord, DataWord, DataWord, DataWord, DataWord)): ProgramState[W, S] = {
    val (gas, target, callValue, inOffset, inSize, outOffset, outSize) = params

    if (state.context.isStaticCall && (this == CALL || this == CALLCODE) && callValue.nonZero) { // alreay in staticCall and call with noZero value
      state.withError(StaticCallModification)
    } else {
      state.resetReturnDataBuffer() // reset before call

      val codeAddress = Address(target)
      val endowment = callValue

      //println(s"ownAddress: ${state.ownAddress} -> codeAddress: $codeAddress with value $callValue in $this")

      val startGas = {
        val gMemIn = state.config.calcMemCost(state.memory.size, inOffset.longValueSafe, inSize.longValueSafe)
        val gMemOut = state.config.calcMemCost(state.memory.size, outOffset.longValueSafe, outSize.longValueSafe)
        val gMem = math.max(gMemIn, gMemOut)

        val gExtra = gasExtra(state, endowment, codeAddress)

        val gAdjust = gasAdjust(state, gas.longValueSafe, gExtra + gMem)
        //if (state.isTraceEnabled) state.addTrace(s"CallOp state.gas: ${state.gas}, gasRequest: ${gas.longValueSafe}, gExtra: $gExtra, gMemIn: $gMemIn, gMemOut: $gMemOut, gMem: $gMem, gAdjust: $gAdjust, endowment: $endowment, ownBalance: ${state.ownBalance}")
        // startGas is calculated as gAdjust and the following G_callstipend if applicable 
        // varGas is calculated as gas that will be consumered
        if (endowment.isZero) gAdjust else gAdjust + state.config.feeSchedule.G_callstipend
      }

      // expand memory according to max in/out offset + in/out size, you know, we've paid gas for it 
      // e.g, tx 0xd31250c86050cb571c548315c0018626989f2fb2385455ec301bd4cdd21ee1c7 should use inOffset + inSize
      if (inOffset.longValueSafe + inSize.longValueSafe > outOffset.longValueSafe + outSize.longValueSafe) {
        state.memory.expand(inOffset.intValueSafe, inSize.intValueSafe)
      } else {
        state.memory.expand(outOffset.intValueSafe, outSize.intValueSafe)
      }

      val isValidCall = state.env.callDepth < EvmConfig.MaxCallDepth && endowment <= state.ownBalance

      if (isValidCall) {
        val (checkpoint, worldAtCheckpoint) = (state.world.copy, state.world)

        def prepareProgramContext(code: ByteString): ProgramContext[W, S] = {
          val input = state.memory.load(inOffset.intValueSafe, inSize.intValueSafe)

          val (owner, caller, value) = this match {
            case CALL         => (codeAddress, state.ownAddress, callValue)
            case STATICCALL   => (codeAddress, state.ownAddress, callValue)
            case CALLCODE     => (state.ownAddress, state.ownAddress, callValue)
            case DELEGATECALL => (state.ownAddress, state.env.callerAddr, state.env.value)
          }

          val env = state.env.copy(
            ownerAddr = owner,
            callerAddr = caller,
            value = value,
            program = Program(code.toArray, state.config),
            input = input,
            callDepth = state.env.callDepth + 1
          )

          val worldAfterTransfer = this match {
            case CALL => worldAtCheckpoint.transfer(state.ownAddress, codeAddress, endowment)
            case _    => worldAtCheckpoint
          }

          state.context.copy(
            env = env,
            targetAddress = codeAddress,
            startGas = startGas,
            world = worldAfterTransfer,
            initialAddressesToDelete = state.addressesToDelete,
            initialAddressesTouched = if (isStateless) state.addressesTouched else state.addressesTouched + codeAddress,
            isStaticCall = state.context.isStaticCall || this.isStatic,
            originalStorageValues = state.context.originalStorageValues
          )
        }

        val result = PrecompiledContracts.getContractForAddress(codeAddress, state.config) match {
          case Some(contract) =>
            val context = prepareProgramContext(ByteString())
            contract.run(context)
          case None =>
            val code = state.world.getCode(codeAddress)
            val context = prepareProgramContext(code)
            VM.run(context, state.isDebugTraceEnabled)
        }

        //println(s"result: $result")

        state.mergeParallelRaceConditions(result.parallelRaceConditions)

        state.withReturnDataBuffer(result.returnData)

        // NOTE even if result.isRevert, we'll still put returnData to memory, 
        // which could be reason message etc that could be used by caller.
        if (result.error.isEmpty) {
          val sizeCap = math.min(outSize.intValueSafe, result.returnData.size)
          if (sizeCap >= 0) {
            val output = result.returnData.take(sizeCap)
            state.memory.store(outOffset.intValueSafe, output)
          }
        }

        if (result.error.isEmpty && !result.isRevert) { // everything ok
          state.stack.push(DataWord.One)

          state
            .spendGas(-result.gasRemaining)
            .refundGas(result.gasRefund)
            .withWorld(result.world)
            .withAddAddressesToDelete(result.addressesToDelete)
            .withAddAddressesTouched(result.addressesTouched)
            .withTxLogs(result.txLogs)
            .step()

        } else {
          state.stack.push(DataWord.Zero)

          //println(s"error in $this: ${error} with result: ${result}")

          // Speical case for #2675119
          // https://github.com/ethereum/go-ethereum/pull/3341/files#r89547994
          // Parity has a bad implementation of EIP 161. That caused account 
          // 0000000000000000000000000000000000000003 to be deleted in block 2675119, 
          // even though the deletion should have been reverted due to an out of gas error. 
          // Geth didn't handle revertals at all (hence today's bug), but because of this,
          // there wasn't a consensus failure 2 days ago. To avoid rewinding the chain,
          //  we added this special case for the Parity bug.
          if (state.config.eip161Patch && codeAddress == PrecompiledContracts.Rip160Addr) {
            state.withAddAddressTouched(codeAddress)
          }

          if (result.isRevert && result.error.isEmpty) {
            state.spendGas(-result.gasRemaining)
          }

          // do not relay error to parent state, since it's only of the sub-routine

          // the error result may be caused by parallel condition, so merge all possible modifies
          state
            .withParallelRaceCondition(ProgramState.OnError)
            .withWorld(checkpoint.mergeRaceConditions(result.world))
            .step()
        }

      } else { // invalid call
        state.stack.push(DataWord.Zero)

        if (endowment <= state.ownBalance) {
          state.withParallelRaceCondition(ProgramState.OnAccount)
        }
        state
          .spendGas(-startGas)
          .step()
      }
    }
  }

  final protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord, DataWord, DataWord, DataWord, DataWord, DataWord)): Long = {
    val (gas, target, callValue, inOffset, inSize, outOffset, outSize) = params

    // TODO how about gas < 0? return a Long.MaxValue?
    val endowment = callValue

    val gMemIn = state.config.calcMemCost(state.memory.size, inOffset.longValueSafe, inSize.longValueSafe)
    val gMemOut = state.config.calcMemCost(state.memory.size, outOffset.longValueSafe, outSize.longValueSafe)
    val gMem = math.max(gMemIn, gMemOut)

    // FIXME: these are calculated twice (for gas and exec), especially account existence. Can we do better? [EC-243]
    val gExtra = gasExtra(state, endowment, Address(target))

    val gAdjust = gasAdjust(state, gas.longValueSafe, gExtra + gMem)
    gExtra + gMem + gAdjust
  }

  private def gasAdjust[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], gRequest: Long, gConsumed: Long): Long = {
    val gLeft = state.gas - gConsumed
    if (state.config.subGasCapDivisor.isDefined && gLeft >= 0) {
      math.min(gRequest, state.config.gasCap(gLeft))
    } else {
      gRequest
    }
  }

  private def gasExtra[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], endowment: DataWord, target: Address): Long = {
    val c_new = this match {
      case CALL =>
        if (state.config.eip161) {
          if (state.world.isAccountDead(target) && endowment.compare(DataWord.Zero) != 0) {
            state.config.feeSchedule.G_newaccount
          } else {
            0
          }
        } else {
          if (!state.world.isAccountExist(target)) {
            state.config.feeSchedule.G_newaccount
          } else {
            0
          }
        }
      case _ =>
        0
    }

    val c_xfer = if (endowment.isZero) 0 else state.config.feeSchedule.G_callvalue
    state.config.feeSchedule.G_call + c_xfer + c_new
  }
}
/**
 * (cxf1) Message-call into an account
 */
case object CALL extends CallOp(0xf1, 7, 1, hasValue = true, isStateless = false, isStatic = false)
/**
 * (0xf2) Calls self, but grabbing the code from the
 * TO argument instead of from one's own address
 */
case object CALLCODE extends CallOp(0xf2, 7, 1, hasValue = true, isStateless = true, isStatic = false)
/**
 * (0xf4)  similar in idea to CALLCODE, except that it propagates the sender and value
 * from the parent scope to the child scope, ie. the call created has the same sender
 * and value as the original call.
 * also the Value parameter is omitted for this opCode
 */
case object DELEGATECALL extends CallOp(0xf4, 6, 1, hasValue = false, isStateless = true, isStatic = false)
/**
 * (0xfa) opcode that can be used to call another contract (or itself) while disallowing any
 * modifications to the state during the call (and its subcalls, if present).
 * Any opcode that attempts to perform such a modification (see below for details)
 * will result in an exception instead of performing the modification.
 */
case object STATICCALL extends CallOp(0xfa, 6, 1, hasValue = false, isStateless = false, isStatic = true)

case object RETURN extends OpCode[(DataWord, DataWord)](0xf3, 2, 0) {
  protected def constGas(s: FeeSchedule) = s.G_zero
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(offset, size) = state.stack.pop(2)
    if (offset.compareTo(DataWord.MaxInt) > 0 || size.compareTo(DataWord.MaxInt) > 0) {
      state.withError(ArithmeticException)
    }
    (offset, size)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): ProgramState[W, S] = {
    val (offset, size) = params
    val data = state.memory.load(offset.intValueSafe, size.intValueSafe)
    state.withReturnData(data).halt()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): Long = {
    val (offset, size) = params
    state.config.calcMemCost(state.memory.size, offset.longValueSafe, size.longValueSafe)
  }
}

case object REVERT extends OpCode[(DataWord, DataWord)](0xfd, 2, 0) {
  protected def constGas(s: FeeSchedule) = s.G_zero
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(offset, size) = state.stack.pop(2)
    if (offset.compareTo(DataWord.MaxInt) > 0 || size.compareTo(DataWord.MaxInt) > 0) {
      state.withError(ArithmeticException)
    }
    (offset, size)
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): ProgramState[W, S] = {
    val (offset, size) = params
    val ret = state.memory.load(offset.intValueSafe, size.intValueSafe)
    state.withReturnData(ret).halt().revert()
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: (DataWord, DataWord)): Long = {
    val (offset, size) = params
    state.config.calcMemCost(state.memory.size, offset.longValueSafe, size.longValueSafe)
  }
}

case object INVALID extends OpCode[Unit](0xfe, 0, 0) with ConstGas[Unit] {
  protected def constGas(s: FeeSchedule) = s.G_zero

  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = ()

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: Unit): ProgramState[W, S] =
    state.withError(InvalidOpCode(code))
}

/**
 * Also as SUICIDE, Renaming SUICIDE opcode to SELFDESTRUCT as in eip-6
 * https://github.com/ethereum/EIPs/blob/master/EIPS/eip-6.md
 */
case object SELFDESTRUCT extends OpCode[DataWord](0xff, 1, 0) {
  protected def constGas(s: FeeSchedule) = s.G_selfdestruct
  protected def getParams[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S]) = {
    val List(refundAddr) = state.stack.pop()
    refundAddr
  }

  protected def exec[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): ProgramState[W, S] = {
    if (state.context.isStaticCall) {
      state.withError(StaticCallModification)
    } else {
      val refundAddr = params
      val refundAddress = Address(refundAddr)

      //println(s"refundAddress: $refundAddress in SELFDESTRUCT")

      val gasRefund = if (state.addressesToDelete contains state.ownAddress) {
        0
      } else {
        state.config.feeSchedule.R_selfdestruct
      }

      val world = state.world.transfer(state.ownAddress, refundAddress, state.ownBalance)

      state
        .withWorld(world)
        .refundGas(gasRefund)
        .withAddAddressToDelete(state.ownAddress)
        .withAddAddressTouched(refundAddress)
        .halt
    }
  }

  protected def variableGas[W <: WorldState[W, S], S <: Storage[S]](state: ProgramState[W, S], params: DataWord): Long = {
    val refundAddr = params
    val refundAddress = Address(refundAddr)

    if (state.config.eip161) {
      if (state.world.isAccountDead(refundAddress) && state.world.getBalance(state.ownAddress).nonZero) {
        state.config.feeSchedule.G_newaccount
      } else {
        0
      }
    } else {
      if (state.config.chargeSelfDestructForNewAccount && !state.world.isAccountExist(refundAddress)) {
        state.config.feeSchedule.G_newaccount
      } else {
        0
      }
    }
  }
}
