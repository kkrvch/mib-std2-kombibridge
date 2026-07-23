// Decompile session-lifecycle candidates + dump xrefs, to find ONE teardown function we can
// BL-patch the way GalReceiver::init is patched -> drop the runtime vtable-copy machinery.
// @category MIB
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.util.task.ConsoleTaskMonitor;
import java.io.PrintWriter;

public class FindDisconnect extends GhidraScript {

    DecompInterface dec;
    long base;
    PrintWriter out;
    void p(String s){ out.println(s); }

    Function funcAt(long va) {
        Address a = toAddr(base + va);
        return currentProgram.getFunctionManager().getFunctionContaining(a);
    }

    void decompile(String name, long va, int maxlines) {
        p("\n==========================================================================");
        p("### " + name + "  @ 0x" + Long.toHexString(va) + " (VA)");
        Function f = funcAt(va);
        if (f == null) { p("  (no function)"); return; }
        DecompileResults res = dec.decompileFunction(f, 60, new ConsoleTaskMonitor());
        if (res == null || !res.decompileCompleted()) { p("  (decompile failed)"); return; }
        String[] lines = res.getDecompiledFunction().getC().split("\n");
        int n = Math.min(lines.length, maxlines);
        for (int i = 0; i < n; i++) p("  " + lines[i]);
        if (lines.length > maxlines) p("  ... (" + (lines.length - maxlines) + " more lines)");
    }

    void xrefs(String name, long va) {
        p("\n--------------------------------------------------------------------------");
        p(">>> XREFS TO " + name + " @ 0x" + Long.toHexString(va));
        Function f = funcAt(va);
        Address target = (f != null) ? f.getEntryPoint() : toAddr(base + va);
        Reference[] refs = getReferencesTo(target);
        int cnt = 0;
        for (Reference r : refs) {
            Address frm = r.getFromAddress();
            Function cf = currentProgram.getFunctionManager().getFunctionContaining(frm);
            p("    from " + frm + "  in " + (cf != null ? cf.getName() : "?")
                    + "  (" + r.getReferenceType() + ")");
            cnt++;
        }
        if (cnt == 0) p("    (no direct xrefs -- called only virtually / via vtable)");
    }

    public void run() throws Exception {
        out = new PrintWriter("/tmp/redump.txt");
        dec = new DecompInterface();
        dec.openProgram(currentProgram);
        base = currentProgram.getImageBase().getOffset();
        p("IMAGE BASE = 0x" + Long.toHexString(base));

        Object[][] C = {
            {"GalReceiver::init",                     0x721b8L},
            {"GalReceiver::shutdown",                 0x7216cL},
            {"GalReceiver::prepareShutdown",          0x721a0L},
            {"GalReceiver::start",                    0x721acL},
            {"GalReceiver::D2_dtor",                  0x728c8L},
            {"MessageRouter::shutdown",               0x8e47cL},
            {"MessageRouter::handleChannelCloseNotif",0x8e508L},
            {"MessageRouter::closeChannel",           0x8e6b4L},
            {"ProtocolEndpointBase::onChannelClosed", 0x747acL},
            {"ChannelManager::shutdown",              0x86634L},
            {"ChannelManager::prepareShutdown",       0x8552cL},
            {"Transport::requestStop",                0x7c858L},
            {"NavigationStatusEndpoint::stop",        0x8a728L},
        };

        for (Object[] c : C) decompile((String)c[0], (Long)c[1], 220);
        for (Object[] c : C) xrefs((String)c[0], (Long)c[1]);
        out.flush();
        out.close();
    }
}
