package rocket

import Chisel._
import Node._;
import Constants._;
import collection.mutable._

class ioTop(htif_width: Int) extends Bundle  {
  val debug   = new ioDebug();
  val host    = new ioHost(htif_width);
  val host_clk = Bool(OUTPUT)
  val mem_backup = new ioMemSerialized
  val mem_backup_en = Bool(INPUT)
  val mem_backup_clk = Bool(OUTPUT)
  val mem     = new ioMem
}

class Top extends Component
{
  val clkdiv = 8
  val htif_width = 8
  val io = new ioTop(htif_width)

  val co =  if(ENABLE_SHARING) {
              if(ENABLE_CLEAN_EXCLUSIVE) new MESICoherence
              else new MSICoherence
            } else {
              if(ENABLE_CLEAN_EXCLUSIVE) new MEICoherence
              else new MICoherence
            }
  val htif = new rocketHTIF(htif_width, NTILES, co)
  val hub = new CoherenceHubBroadcast(NTILES+1, co)
  val llc_leaf = Mem(2048, seqRead = true) { Bits(width = 64) }
  val llc = new DRAMSideLLC(2048, 8, 4, llc_leaf, llc_leaf)
  hub.io.tiles(NTILES) <> htif.io.mem

  llc.io.cpu.req_cmd <> Queue(hub.io.mem.req_cmd)
  llc.io.cpu.req_data <> Queue(hub.io.mem.req_data, REFILL_CYCLES)
  hub.io.mem.resp <> llc.io.cpu.resp

  // mux between main and backup memory ports
  val mem_serdes = new MemSerdes
  val mem_cmdq = (new queue(2)) { new MemReqCmd }
  mem_cmdq.io.enq <> llc.io.mem.req_cmd
  mem_cmdq.io.deq.ready := Mux(io.mem_backup_en, mem_serdes.io.wide.req_cmd.ready, io.mem.req_cmd.ready)
  io.mem.req_cmd.valid := mem_cmdq.io.deq.valid && !io.mem_backup_en
  io.mem.req_cmd.bits := mem_cmdq.io.deq.bits
  mem_serdes.io.wide.req_cmd.valid := mem_cmdq.io.deq.valid && io.mem_backup_en
  mem_serdes.io.wide.req_cmd.bits := mem_cmdq.io.deq.bits

  val mem_dataq = (new queue(REFILL_CYCLES)) { new MemData }
  mem_dataq.io.enq <> llc.io.mem.req_data
  mem_dataq.io.deq.ready := Mux(io.mem_backup_en, mem_serdes.io.wide.req_data.ready, io.mem.req_data.ready)
  io.mem.req_data.valid := mem_dataq.io.deq.valid && !io.mem_backup_en
  io.mem.req_data.bits := mem_dataq.io.deq.bits
  mem_serdes.io.wide.req_data.valid := mem_dataq.io.deq.valid && io.mem_backup_en
  mem_serdes.io.wide.req_data.bits := mem_dataq.io.deq.bits

  llc.io.mem.resp.valid := Mux(io.mem_backup_en, mem_serdes.io.wide.resp.valid, io.mem.resp.valid)
  llc.io.mem.resp.bits := Mux(io.mem_backup_en, mem_serdes.io.wide.resp.bits, io.mem.resp.bits)

  // pad out the HTIF using a divided clock
  val hio = (new slowIO(clkdiv)) { Bits(width = htif_width) }
  htif.io.host.out <> hio.io.out_fast
  io.host.out <> hio.io.out_slow
  htif.io.host.in <> hio.io.in_fast
  io.host.in <> hio.io.in_slow
  io.host_clk := hio.io.clk_slow

  // pad out the backup memory link with the HTIF divided clk
  val mio = (new slowIO(clkdiv)) { Bits(width = MEM_BACKUP_WIDTH) }
  mem_serdes.io.narrow.req <> mio.io.out_fast
  io.mem_backup.req <> mio.io.out_slow
  mem_serdes.io.narrow.resp.valid := mio.io.in_fast.valid
  mio.io.in_fast.ready := Bool(true)
  mem_serdes.io.narrow.resp.bits := mio.io.in_fast.bits
  io.mem_backup.resp <> mio.io.in_slow
  io.mem_backup_clk := mio.io.clk_slow

  var error_mode = Bool(false)
  for (i <- 0 until NTILES) {
    val tile = new Tile(co)
    tile.io.host <> htif.io.cpu(i)
    hub.io.tiles(i) <> tile.io.tilelink
    error_mode = error_mode || tile.io.host.debug.error_mode
  }
  io.debug.error_mode := error_mode
}

object top_main {
  def main(args: Array[String]): Unit = { 
    val top = args(0)
    val chiselArgs = ArrayBuffer[String]()

    var i = 1
    while (i < args.length) {
      val arg = args(i)
      arg match {
        case "--NUM_PVFB" => {
          hwacha.Constants.NUM_PVFB = args(i+1).toInt
          i += 1
        }
        case "--WIDTH_PVFB" => {
          hwacha.Constants.WIDTH_PVFB = args(i+1).toInt
          hwacha.Constants.DEPTH_PVFB = args(i+1).toInt
          i += 1
        }
        case "--CG" => {
          hwacha.Constants.coarseGrained = true
        }
        case any => chiselArgs += arg
      }
      i += 1
    }

    chiselMain(chiselArgs.toArray, () => Class.forName(top).newInstance.asInstanceOf[Component])
  }
}
