/*
 * TierraLab.java
 *
 * A single-file Java Swing laboratory recreating Tierra, Thomas S. Ray's
 * landmark artificial-life system (1990-91): self-replicating machine-code
 * programs living in the memory of a virtual computer, competing for CPU time
 * and space, mutating and evolving. Seeded with a single hand-written ancestor,
 * the soup evolves smaller replicators, parasites that borrow their neighbours'
 * code, and creatures that live partly on others.
 *
 *   T. S. Ray, "An Approach to the Synthesis of Life", in Artificial Life II,
 *   Santa Fe Institute Studies in the Sciences of Complexity, vol. XI, 1991.
 *
 * This is an independent implementation of Ray's virtual machine (instruction
 * set 0), following his published descriptions and the default parameters of
 * his Tierra 6.02 simulator: a 60,000-instruction soup, template addressing,
 * write-protected memory, a time slicer, a reaper queue, and cosmic-ray,
 * copy-error and flaw mutations. The ancestor (0080aaa) and the archetypal
 * parasite (0045aaa) are Ray's.
 *
 * Run:   java TierraLab.java        (Java 17 or newer)
 *
 * Architect: Mario Gianota
 * Implementation: Claude (Anthropic)
 * Licence: MIT (this implementation). Tierra itself is Copyright (c) Thomas S. Ray.
 */

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class TierraLab extends JFrame {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new TierraLab().setVisible(true);
        });
    }

    // =====================================================================
    //  The Tierra virtual machine
    // =====================================================================

    static final class Soup {
    static final String[] MNEMONIC = {
        "nop0", "nop1", "not0", "shl", "zero", "ifz", "subCAB", "subAAC",
        "incA", "incB", "decC", "incC", "pushA", "pushB", "pushC", "pushD",
        "popA", "popB", "popC", "popD", "jmpo", "jmpb", "call", "ret",
        "movDC", "movBA", "movii", "adro", "adrb", "adrf", "mal", "divide"};

    static final int NOP0 = 0, NOP1 = 1, NOT0 = 2, SHL = 3, ZERO = 4, IFZ = 5, SUBCAB = 6, SUBAAC = 7,
        INCA = 8, INCB = 9, DECC = 10, INCC = 11, PUSHA = 12, PUSHB = 13, PUSHC = 14, PUSHD = 15,
        POPA = 16, POPB = 17, POPC = 18, POPD = 19, JMPO = 20, JMPB = 21, CALL = 22, RET = 23,
        MOVDC = 24, MOVBA = 25, MOVII = 26, ADRO = 27, ADRB = 28, ADRF = 29, MAL = 30, DIVIDE = 31;

    /** Ray's hand-written ancestor, genotype 0080aaa (Ray 1991, Appendix C). */
    static final String ANCESTOR =
        "nop1 nop1 nop1 nop1 zero not0 shl shl movDC adrb nop0 nop0 nop0 nop0 subAAC movBA " +
        "adrf nop0 nop0 nop0 nop1 incA subCAB nop1 nop1 nop0 nop1 mal call nop0 nop0 nop1 nop1 " +
        "divide jmpo nop0 nop0 nop1 nop0 ifz nop1 nop1 nop0 nop0 pushA pushB pushC nop1 nop0 nop1 nop0 " +
        "movii decC ifz jmpo nop0 nop1 nop0 nop0 incA incB jmpo nop0 nop1 nop0 nop1 ifz " +
        "nop1 nop0 nop1 nop1 popC popB popA ret nop1 nop1 nop1 nop0 ifz";

    /** The archetypal parasite, genotype 0045aaa, from Ray's genebank. */
    static final String PARASITE =
        "nop1 nop1 nop1 nop1 zero not0 shl shl movDC adrb nop0 nop0 nop0 nop0 subAAC movBA " +
        "adrf nop0 nop0 nop0 nop1 incA subCAB nop1 nop1 nop0 nop1 mal call nop0 nop0 nop1 nop1 " +
        "divide jmpo nop0 nop0 nop1 nop0 ifz nop1 nop1 nop1 nop0 pushA";

    static byte[] assemble(String src) {
        String[] t = src.trim().split("\\s+");
        byte[] g = new byte[t.length];
        for (int i = 0; i < t.length; i++) {
            int op = -1;
            for (int k = 0; k < 32; k++) if (MNEMONIC[k].equals(t[i])) op = k;
            if (op < 0) throw new IllegalArgumentException(t[i]);
            g[i] = (byte) op;
        }
        return g;
    }

    // ---------------------------------------------------------------------

    static final class Genotype {
        final String key, name;
        final int size;
        final byte[] genome;
        int alive, born;
        long firstSeen;
        long foreignExec, ownExec;     // instructions executed in other creatures' memory vs own
        Genotype(String key, String name, byte[] genome, long when) {
            this.key = key; this.name = name; this.genome = genome; this.size = genome.length; this.firstSeen = when;
        }
        double foreignFraction() { long t = foreignExec + ownExec; return t == 0 ? 0 : (double) foreignExec / t; }
    }

    static final class Creature {
        final int id;
        int start, size;                 // own memory block
        int dStart = -1, dSize = 0;      // daughter block (owned by this creature until divide)
        int ax, bx, cx, dx, ip, sp;
        final int[] stack = new int[10];
        int errors;
        int movDaught, movMin, movMax;
        Genotype genotype;
        long born;
        long executed;
        int children;
        long lastDivExec;
        boolean alive = true;
        // reaper doubly linked list
        Creature up, down;
        Creature(int id) { this.id = id; }
    }

    // ---------------------------------------------------------------------

    final int soupSize;
    final byte[] soup;
    final int[] owner;               // creature id owning each cell, or -1 if free
    final HashMap<Integer, Creature> byId = new HashMap<>();
    final ArrayList<Creature> slicer = new ArrayList<>();
    int slicerPos = 0;
    Creature reapTop, reapBottom;    // top = next to die
    int population = 0;
    int nextId = 1;
    final Random rng;
    final TreeMap<Integer, Integer> free = new TreeMap<>();   // start -> length

    // Parameters (Ray's defaults for instruction set 0)
    int sliceSize = 25;
    double searchLimit = 5;
    int minCellSize = 12;
    int maxMalMult = 3;               // a daughter may be at most 3x its mother's size
    double movPropThrDiv = 0.7;
    double reapRndProp = 0.3;
    double mutBitProp = 0.2;
    double lazyTol = 10;              // kill creatures that go this many reproduction-times without reproducing
    long lazyDeaths = 0;
    int genPerBkgMut = 32, genPerFlaw = 32, genPerMovMut = 32;

    // Statistics
    long instExecuted = 0;
    long births = 0, deaths = 0;
    long cosmicRays = 0, copyMutations = 0, flaws = 0;
    double avgSize = 80;
    double repInst = 800;            // instructions per reproduction (running estimate)
    long countMut, countFlaw, countMovMut;
    long rateMut = 0, rateFlaw = 0, rateMovMut = 0;
    boolean flawPending = false;
    final HashMap<String, Genotype> genotypes = new HashMap<>();
    final HashMap<Integer, Integer> suffixCounter = new HashMap<>();

    Soup(int soupSize, long seed) {
        this.soupSize = soupSize;
        soup = new byte[soupSize];
        owner = new int[soupSize];
        Arrays.fill(owner, -1);
        rng = new Random(seed);
        for (int i = 0; i < soupSize; i++) soup[i] = (byte) rng.nextInt(32);   // random background
        free.put(0, soupSize);
        recalcRates();
    }

    int ad(int a) { a %= soupSize; return a < 0 ? a + soupSize : a; }

    // ---------------- memory ---------------------------------------------

    /** Better fit: the smallest free block that is large enough. */
    int allocate(int size) {
        int best = -1, bestLen = Integer.MAX_VALUE;
        for (Map.Entry<Integer, Integer> e : free.entrySet())
            if (e.getValue() >= size && e.getValue() < bestLen) { best = e.getKey(); bestLen = e.getValue(); if (bestLen == size) break; }
        if (best < 0) return -1;
        free.remove(best);
        if (bestLen > size) free.put(best + size, bestLen - size);
        return best;
    }

    void release(int start, int size) {
        for (int i = 0; i < size; i++) owner[start + i] = -1;
        int s = start, len = size;
        Map.Entry<Integer, Integer> lo = free.floorEntry(s);
        if (lo != null && lo.getKey() + lo.getValue() == s) { s = lo.getKey(); len += lo.getValue(); free.remove(lo.getKey()); }
        Integer hiLen = free.get(start + size);
        if (hiLen != null) { len += hiLen; free.remove(start + size); }
        free.put(s, len);
    }

    int usedMemory() { int f = 0; for (int v : free.values()) f += v; return soupSize - f; }

    // ---------------- reaper ---------------------------------------------

    void reaperAddBottom(Creature c) {
        c.down = null; c.up = reapBottom;
        if (reapBottom != null) reapBottom.down = c; else reapTop = c;
        reapBottom = c;
    }

    void reaperRemove(Creature c) {
        if (c.up != null) c.up.down = c.down; else reapTop = c.down;
        if (c.down != null) c.down.up = c.up; else reapBottom = c.up;
        c.up = c.down = null;
    }

    /** Swap c with the creature above it (towards death), if c has at least as many errors. */
    void reaperUp(Creature c) {
        Creature u = c.up;
        if (u == null || c.errors < u.errors) return;
        reaperRemove(c);
        c.up = u.up; c.down = u;
        if (u.up != null) u.up.down = c; else reapTop = c;
        u.up = c;
    }

    /** Swap c with the creature below it (away from death), if c has no more errors than it. */
    void reaperDown(Creature c) {
        Creature d = c.down;
        if (d == null || c.errors > d.errors) return;
        reaperRemove(c);
        c.down = d.down; c.up = d;
        if (d.down != null) d.down.up = c; else reapBottom = c;
        d.down = c;
    }

    /** Kill a creature chosen at random from the top portion of the reaper queue. */
    void reap() {
        if (reapTop == null) return;
        int span = Math.max(1, (int) (population * reapRndProp));
        int k = rng.nextInt(span);
        Creature c = reapTop;
        for (int i = 0; i < k && c.down != null; i++) c = c.down;
        kill(c);
    }

    void kill(Creature c) {
        if (!c.alive) return;
        c.alive = false;
        reaperRemove(c);
        int idx = slicer.indexOf(c);
        if (idx >= 0) { slicer.remove(idx); if (idx < slicerPos) slicerPos--; }
        release(c.start, c.size);
        if (c.dStart >= 0) release(c.dStart, c.dSize);
        byId.remove(c.id);
        population--;
        deaths++;
        if (c.genotype != null) c.genotype.alive--;
    }

    // ---------------- genotypes ------------------------------------------

    Genotype genotypeOf(int start, int size) {
        byte[] g = Arrays.copyOfRange(soup, start, start + size);
        String key = new String(g, java.nio.charset.StandardCharsets.ISO_8859_1);
        Genotype gt = genotypes.get(key);
        if (gt == null) {
            int n = suffixCounter.merge(size, 1, Integer::sum) - 1;
            String suffix = "" + (char) ('a' + (n / 676) % 26) + (char) ('a' + (n / 26) % 26) + (char) ('a' + n % 26);
            gt = new Genotype(key, String.format("%04d%s", size, suffix), g, instExecuted);
            genotypes.put(key, gt);
        }
        return gt;
    }

    // ---------------- creatures ------------------------------------------

    Creature inject(byte[] genome, int at) {
        int size = genome.length;
        int start;
        if (at >= 0) {
            // carve the block out of the free list at a chosen place, if free
            Map.Entry<Integer, Integer> e = free.floorEntry(at);
            if (e == null || e.getKey() + e.getValue() < at + size) return null;
            free.remove(e.getKey());
            if (at > e.getKey()) free.put(e.getKey(), at - e.getKey());
            if (e.getKey() + e.getValue() > at + size) free.put(at + size, e.getKey() + e.getValue() - at - size);
            start = at;
        } else {
            start = allocate(size);
            while (start < 0 && population > 0) { reap(); start = allocate(size); }
            if (start < 0) return null;
        }
        System.arraycopy(genome, 0, soup, start, size);
        Creature c = new Creature(nextId++);
        c.start = start; c.size = size; c.ip = start; c.born = instExecuted;
        for (int i = 0; i < size; i++) owner[start + i] = c.id;
        c.genotype = genotypeOf(start, size);
        c.genotype.alive++; c.genotype.born++;
        byId.put(c.id, c);
        slicer.add(c);
        reaperAddBottom(c);
        population++;
        return c;
    }

    void recalcRates() {
        double popGenTime = Math.max(1, population) * repInst;
        double probHit = avgSize / soupSize;
        rateMut = genPerBkgMut > 0 ? Math.max(1, (long) (popGenTime * 2.0 * genPerBkgMut * probHit)) : 0;
        rateFlaw = genPerFlaw > 0 ? Math.max(1, (long) (repInst * genPerFlaw * 2)) : 0;
        rateMovMut = genPerMovMut > 0 ? Math.max(1, (long) (2L * genPerMovMut * avgSize)) : 0;
    }

    void mutateSite(int a) {
        if (rng.nextDouble() < mutBitProp) soup[a] = (byte) (soup[a] ^ (1 << rng.nextInt(5)));
        else soup[a] = (byte) rng.nextInt(32);
    }

    /** A flaw: the result of the next affected operation is off by one. */
    int flaw() {
        if (!flawPending) return 0;
        flawPending = false;
        flaws++;
        return rng.nextBoolean() ? 1 : -1;
    }

    boolean canWrite(Creature c, int a) {
        int o = owner[a];
        return o == -1 || o == c.id;      // free memory, own memory, or own daughter (owned by c until divide)
    }

    int templateLength(int ip) {
        int n = 0;
        while (n < 64) { int b = soup[ad(ip + 1 + n)]; if (b != NOP0 && b != NOP1) break; n++; }
        return n;
    }

    /**
     * Search for the complement of the template that follows the instruction at ip.
     * dir: 'o' outward (both ways), 'f' forward, 'b' backward. Returns the address just after the
     * matching template, or -1 if none is found within the search limit.
     */
    int findTemplate(int ip, int tz, char dir) {
        int limit = (int) (searchLimit * avgSize);
        boolean df = dir != 'b', db = dir != 'f';
        int f = ad(ip + tz + 1), b = ad(ip - 1);
        for (int dist = 0; dist < limit; dist++) {
            if (df && matches(ip, tz, f)) return ad(f + tz);
            if (db && matches(ip, tz, b)) return ad(b + tz);
            f = ad(f + 1); b = ad(b - 1);
        }
        return -1;
    }

    boolean matches(int ip, int tz, int at) {
        for (int i = 0; i < tz; i++) {
            int t = soup[ad(ip + 1 + i)], s = soup[ad(at + i)];
            if (s != NOP0 && s != NOP1) return false;
            if (t + s != 1) return false;          // complementary: nop0 <-> nop1
        }
        return true;
    }

    void push(Creature c, int v) { c.sp = (c.sp + 1) % c.stack.length; c.stack[c.sp] = v + flaw(); }

    int pop(Creature c) { int v = c.stack[c.sp] + flaw(); c.sp = c.sp == 0 ? c.stack.length - 1 : c.sp - 1; return v; }

    void error(Creature c) { c.errors++; reaperUp(c); }

    /** Execute one instruction for creature c. */
    void execute(Creature c) {
        int ip = c.ip;
        int op = soup[ip];
        int next = ad(ip + 1);
        int ownerHere = owner[ip];
        if (ownerHere != c.id) c.genotype.foreignExec++; else c.genotype.ownExec++;
        switch (op) {
            case NOP0, NOP1 -> { }
            case NOT0 -> c.cx = (c.cx ^ 1) + flaw();
            case SHL -> c.cx = (c.cx << 1) + flaw();
            case ZERO -> c.cx = 0 + flaw();
            case IFZ -> { if (c.cx != 0) next = ad(ip + 2); }
            case SUBCAB -> c.cx = c.ax - c.bx + flaw();
            case SUBAAC -> c.ax = c.ax - c.cx + flaw();
            case INCA -> c.ax = c.ax + 1 + flaw();
            case INCB -> c.bx = c.bx + 1 + flaw();
            case DECC -> c.cx = c.cx - 1 + flaw();
            case INCC -> c.cx = c.cx + 1 + flaw();
            case PUSHA -> push(c, c.ax);
            case PUSHB -> push(c, c.bx);
            case PUSHC -> push(c, c.cx);
            case PUSHD -> push(c, c.dx);
            case POPA -> c.ax = pop(c);
            case POPB -> c.bx = pop(c);
            case POPC -> c.cx = pop(c);
            case POPD -> c.dx = pop(c);
            case MOVDC -> c.dx = c.cx + flaw();
            case MOVBA -> c.bx = c.ax + flaw();
            case JMPO, JMPB, CALL -> {
                int tz = templateLength(ip);
                if (tz == 0) {
                    if (op == CALL) error(c);
                    else next = ad(c.bx);                       // no template: jump to address in bx
                } else {
                    int t = findTemplate(ip, tz, op == JMPB ? 'b' : 'o');
                    if (t < 0) { error(c); next = ad(ip + tz + 1); }
                    else {
                        if (op == CALL) push(c, ad(ip + tz + 1));
                        next = t;
                    }
                }
            }
            case RET -> next = ad(pop(c));
            case ADRO, ADRB, ADRF -> {
                int tz = templateLength(ip);
                if (tz == 0) { error(c); }
                else {
                    int t = findTemplate(ip, tz, op == ADRO ? 'o' : op == ADRB ? 'b' : 'f');
                    if (t < 0) error(c);
                    else { c.ax = t + flaw(); c.cx = tz; }
                    next = ad(ip + tz + 1);
                }
            }
            case MOVII -> {
                int to = c.ax, from = c.bx;
                if (to == from || to < 0 || to >= soupSize || from < 0 || from >= soupSize || !canWrite(c, to)) error(c);
                else {
                    soup[to] = soup[from];
                    if (rateMovMut > 0 && ++countMovMut >= rateMovMut) {
                        mutateSite(to); copyMutations++;
                        countMovMut = rng.nextLong(rateMovMut);
                    }
                    if (c.dStart >= 0 && to >= c.dStart && to < c.dStart + c.dSize) {
                        int off = to - c.dStart;
                        if (c.movDaught == 0) c.movMin = c.movMax = off;
                        c.movMin = Math.min(c.movMin, off); c.movMax = Math.max(c.movMax, off);
                        c.movDaught++;
                    }
                }
            }
            case MAL -> {
                int size = c.cx;
                if (size < minCellSize || size > maxMalMult * c.size || size >= soupSize / 4) error(c);   // Ray's MaxMalMult
                else {
                    if (c.dStart >= 0) { release(c.dStart, c.dSize); c.dStart = -1; }
                    int a = allocate(size);
                    int guard = 0;
                    while (a < 0 && population > 1 && guard++ < 1000) {
                        reap();
                        if (!c.alive) return;               // the reaper took the caller itself
                        a = allocate(size);
                    }
                    if (a < 0) error(c);
                    else {
                        for (int i = 0; i < size; i++) owner[a + i] = c.id;
                        c.dStart = a; c.dSize = size; c.movDaught = 0;
                        c.ax = a;
                    }
                }
            }
            case DIVIDE -> {
                int span = c.movDaught == 0 ? 0 : c.movMax - c.movMin + 1;
                int thresh = (int) (c.dSize * movPropThrDiv);
                if (c.dStart < 0 || c.dSize < minCellSize || span < thresh || c.movDaught < thresh) error(c);
                else divide(c);
            }
            default -> { }
        }
        c.ip = next;
        c.executed++;
        instExecuted++;
        // cosmic rays and flaws, at rates scaled as in Ray's code
        if (rateMut > 0 && ++countMut >= rateMut) { mutateSite(rng.nextInt(soupSize)); cosmicRays++; countMut = rng.nextLong(rateMut); }
        if (rateFlaw > 0 && ++countFlaw >= rateFlaw) { flawPending = true; countFlaw = rng.nextLong(rateFlaw); }
    }

    void divide(Creature m) {
        Creature d = new Creature(nextId++);
        d.start = m.dStart; d.size = m.dSize; d.ip = d.start; d.born = instExecuted;
        for (int i = 0; i < d.size; i++) owner[d.start + i] = d.id;
        d.genotype = genotypeOf(d.start, d.size);
        d.genotype.alive++; d.genotype.born++;
        byId.put(d.id, d);
        // daughter enters the slicer just before the mother, and the bottom of the reaper queue
        int mi = slicer.indexOf(m);
        slicer.add(Math.max(0, mi), d);
        if (mi >= 0 && mi < slicerPos) slicerPos++;
        reaperAddBottom(d);
        population++;
        births++;
        m.children++;
        m.dStart = -1; m.dSize = 0; m.movDaught = 0;
        reaperDown(m);
        // update running averages used for mutation rates
        avgSize = avgSize * 0.99 + d.size * 0.01;
        repInst = repInst * 0.99 + (m.executed - m.lastDivExec) * 0.01;
        m.lastDivExec = m.executed;
        if (births % 64 == 0) recalcRates();
    }

    /** Run roughly n instructions, giving each creature its time slice in turn. */
    void run(long n) {
        long target = instExecuted + n;
        while (instExecuted < target && population > 0) {
            if (slicerPos >= slicer.size()) slicerPos = 0;
            Creature c = slicer.get(slicerPos);
            int slice = rng.nextInt(2 * sliceSize + 1);   // SliceStyle 2: random, mean = SliceSize
            for (int i = 0; i < slice && c.alive; i++) execute(c);
            if (c.alive && population > 1 && lazyTol > 0 && c.executed - c.lastDivExec > lazyTol * repInst) { kill(c); lazyDeaths++; }
            if (c.alive) slicerPos++;
            else if (slicerPos >= slicer.size()) slicerPos = 0;
        }
    }
    }

    // =====================================================================
    //  Roles and colours
    // =====================================================================

    enum Role {
        UNKNOWN("new", new Color(150, 150, 160)),
        SELF("self-replicator", new Color(70, 200, 110)),
        MIXED("lives partly on others", new Color(240, 180, 50)),
        PARASITE("parasite", new Color(235, 70, 70));
        final String label; final Color colour;
        Role(String l, Color c) { label = l; colour = c; }
    }

    static Role roleOf(Soup.Genotype g) {
        if (g.foreignExec + g.ownExec < 1500) return Role.UNKNOWN;
        double f = g.foreignFraction();
        return f < 0.2 ? Role.SELF : f > 0.7 ? Role.PARASITE : Role.MIXED;
    }

    enum ColourBy {
        ROLE("Role (host / parasite)"), SIZE("Genome size");
        final String label;
        ColourBy(String l) { label = l; }
        @Override public String toString() { return label; }
    }

    static final int MAP_W = 300, MAP_H = 200;   // 60,000 cells

    // =====================================================================
    //  State
    // =====================================================================

    private volatile Soup soup;
    private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
    private volatile boolean running = true, singleStep = false;
    private volatile long instPerFrame = 400_000;
    private volatile ColourBy colourBy = ColourBy.ROLE;
    private volatile BufferedImage frame;
    private volatile double instPerSec;
    private volatile int selectedAddr = -1;
    private volatile String selectedGenotype = null;

    // snapshots for the UI, refreshed by the simulation thread
    private volatile List<Row> rows = List.of();
    private volatile int[] sizeHist = new int[161];
    private volatile Role[] sizeRole = new Role[161];
    private final ArrayList<int[]> sizeHistory = new ArrayList<>();      // per sample: count per size 0..160
    private final ArrayList<Long> sizeHistoryTime = new ArrayList<>();
    private final Object histLock = new Object();
    private volatile String statusText = " ";
    private volatile String inspectText = "Click a creature in the soup, or a genotype in the table, to read its code.";

    // landmarks
    private boolean firstParasiteSeen = false, firstMixedSeen = false;
    private int smallestSelf = Integer.MAX_VALUE;
    private boolean extinctReported = false;
    private final DefaultListModel<String> events = new DefaultListModel<>();

    record Row(String name, int size, int alive, Role role, double foreign, long firstSeen) { }

    private final SoupView soupView = new SoupView();
    private final SizeHistogram histogram = new SizeHistogram();
    private final SizeTimeChart sizeTime = new SizeTimeChart();
    private final GenotypeModel tableModel = new GenotypeModel();
    private final JTable table = new JTable(tableModel);
    private final JTextArea inspector = new JTextArea(14, 34);
    private final JLabel status = new JLabel(" ");

    private final JTextField seedField = new JTextField("1", 7);
    private final JToggleButton playButton = new JToggleButton("Pause", true);
    private final ParamSlider speedSlider = new ParamSlider("Speed (instructions per frame, thousands)", 10, 2000, 400, "%.0f");
    private final JCheckBox cosmicBox = new JCheckBox("Cosmic rays", true);
    private final JCheckBox copyBox = new JCheckBox("Copy errors", true);
    private final JCheckBox flawBox = new JCheckBox("Flaws", true);
    private final JComboBox<ColourBy> colourBox = new JComboBox<>(ColourBy.values());

    public TierraLab() {
        super("Tierra Lab — evolution in the memory of a virtual computer (after Thomas S. Ray)");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        soup = newSoup(1);
        buildUI();
        logEvent("Soup inoculated with the ancestor, 0080aaa (seed 1)");

        Thread worker = new Thread(this::loop, "tierra-sim");
        worker.setDaemon(true);
        worker.start();
        new javax.swing.Timer(100, e -> refreshUI()).start();
        pack();
        setLocationRelativeTo(null);
    }

    private Soup newSoup(long seed) {
        Soup s = new Soup(60000, seed);
        s.inject(Soup.assemble(Soup.ANCESTOR), 30000);
        firstParasiteSeen = firstMixedSeen = false;
        smallestSelf = Integer.MAX_VALUE;
        extinctReported = false;
        synchronized (histLock) { sizeHistory.clear(); sizeHistoryTime.clear(); }
        return s;
    }

    // =====================================================================
    //  Simulation loop
    // =====================================================================

    private void loop() {
        BufferedImage[] bufs = {new BufferedImage(MAP_W, MAP_H, BufferedImage.TYPE_INT_RGB), new BufferedImage(MAP_W, MAP_H, BufferedImage.TYPE_INT_RGB)};
        int bi = 0, frameNo = 0;
        int[] px = new int[MAP_W * MAP_H];
        long lastT = System.nanoTime(), lastInst = 0;
        long lastSample = 0;
        while (true) {
            long start = System.nanoTime();
            Runnable t;
            while ((t = tasks.poll()) != null) t.run();
            Soup s = soup;
            int gb = cosmicBox.isSelected() ? 32 : 0, gm = copyBox.isSelected() ? 32 : 0, gf = flawBox.isSelected() ? 32 : 0;
            if (gb != s.genPerBkgMut || gm != s.genPerMovMut || gf != s.genPerFlaw) {
                s.genPerBkgMut = gb; s.genPerMovMut = gm; s.genPerFlaw = gf; s.recalcRates();
            }
            boolean advance = (running || singleStep) && s.population > 0;
            if (advance) {
                long n = singleStep && !running ? 1 : instPerFrame;
                singleStep = false;
                if (n == 1) { s.recalcRates(); s.run(1); }
                else s.run(n);
            }
            render(s, px);
            BufferedImage img = bufs[bi];
            bi ^= 1;
            img.setRGB(0, 0, MAP_W, MAP_H, px, 0, MAP_W);
            frame = img;

            if (frameNo % 3 == 0) snapshot(s);
            if (s.instExecuted - lastSample >= 1_000_000) { lastSample = s.instExecuted; sampleSizes(s); }
            frameNo++;

            long now = System.nanoTime();
            if (now - lastT > 1_000_000_000L) { instPerSec = (s.instExecuted - lastInst) * 1e9 / (now - lastT); lastInst = s.instExecuted; lastT = now; }
            long sleep = 30 - (System.nanoTime() - start) / 1_000_000;
            try { Thread.sleep(Math.max(2, sleep)); } catch (InterruptedException ex) { return; }
        }
    }

    private static int sizeColour(int size, boolean daughter, boolean odd) {
        float hue = (float) Math.max(0, Math.min(0.8, (size - 15) / 110.0 * 0.8));
        float br = daughter ? 0.45f : odd ? 0.95f : 0.78f;
        return Color.HSBtoRGB(hue, 0.8f, br);
    }

    private void render(Soup s, int[] px) {
        int n = s.soupSize;
        HashMap<Integer, Soup.Creature> byId = s.byId;
        // map daughter blocks to their mothers for shading
        for (int i = 0; i < n; i++) {
            int o = s.owner[i];
            if (o < 0) { int b = s.soup[i]; int g = 14 + (b & 7); px[i] = (g << 16) | (g << 8) | (g + 6); continue; }
            Soup.Creature c = byId.get(o);
            if (c == null) { px[i] = 0x202020; continue; }
            boolean daughter = c.dStart >= 0 && i >= c.dStart && i < c.dStart + c.dSize;
            boolean odd = (c.id & 1) == 1;
            if (colourBy == ColourBy.SIZE) px[i] = sizeColour(c.size, daughter, odd);
            else {
                Color col = roleOf(c.genotype).colour;
                float[] hsb = Color.RGBtoHSB(col.getRed(), col.getGreen(), col.getBlue(), null);
                float br = daughter ? 0.42f : odd ? 0.98f : 0.74f;
                px[i] = Color.HSBtoRGB(hsb[0], hsb[1], br);
            }
        }
        int sel = selectedAddr;
        if (sel >= 0 && sel < n && s.owner[sel] >= 0) {
            Soup.Creature c = byId.get(s.owner[sel]);
            if (c != null) for (int i = c.start; i < c.start + c.size; i++) px[i] = 0xffffff;
        }
    }

    private void sampleSizes(Soup s) {
        int[] counts = new int[161];
        for (Soup.Creature c : s.byId.values()) counts[Math.min(160, c.size)]++;
        synchronized (histLock) {
            sizeHistory.add(counts);
            sizeHistoryTime.add(s.instExecuted);
            if (sizeHistory.size() > 1200) {
                ArrayList<int[]> h = new ArrayList<>(); ArrayList<Long> tt = new ArrayList<>();
                for (int i = 0; i < sizeHistory.size(); i += 2) { h.add(sizeHistory.get(i)); tt.add(sizeHistoryTime.get(i)); }
                sizeHistory.clear(); sizeHistory.addAll(h); sizeHistoryTime.clear(); sizeHistoryTime.addAll(tt);
            }
        }
    }

    private void snapshot(Soup s) {
        // genotype table
        ArrayList<Soup.Genotype> living = new ArrayList<>();
        for (Soup.Genotype g : s.genotypes.values()) if (g.alive > 0) living.add(g);
        living.sort((a, b) -> b.alive - a.alive);
        ArrayList<Row> r = new ArrayList<>();
        for (int i = 0; i < Math.min(60, living.size()); i++) {
            Soup.Genotype g = living.get(i);
            r.add(new Row(g.name, g.size, g.alive, roleOf(g), g.foreignFraction(), g.firstSeen));
        }
        rows = r;
        // size histogram, coloured by the dominant role at each size
        int[] h = new int[161];
        int[][] roleCount = new int[161][Role.values().length];
        for (Soup.Creature c : s.byId.values()) {
            int sz = Math.min(160, c.size);
            h[sz]++;
            roleCount[sz][roleOf(c.genotype).ordinal()]++;
        }
        Role[] rr = new Role[161];
        for (int i = 0; i < 161; i++) {
            int best = 0;
            for (int k = 1; k < Role.values().length; k++) if (roleCount[i][k] > roleCount[i][best]) best = k;
            rr[i] = Role.values()[best];
        }
        sizeHist = h; sizeRole = rr;
        // landmarks
        for (Soup.Genotype g : living) {
            if (g.alive < 5) continue;
            Role role = roleOf(g);
            if (role == Role.PARASITE && !firstParasiteSeen) {
                firstParasiteSeen = true;
                logEvent(String.format("%.0fM instructions: first parasites, %s (%d instructions, %.0f%% of its time in others' code)",
                        s.instExecuted / 1e6, g.name, g.size, 100 * g.foreignFraction()));
            }
            if (role == Role.MIXED && !firstMixedSeen) {
                firstMixedSeen = true;
                logEvent(String.format("%.0fM instructions: creatures living partly on others appear, %s (%.0f%% foreign)",
                        s.instExecuted / 1e6, g.name, 100 * g.foreignFraction()));
            }
            if (role == Role.SELF && g.size < smallestSelf) {
                if (smallestSelf != Integer.MAX_VALUE && g.size <= 75)
                    logEvent(String.format("%.0fM instructions: new smallest self-replicator, %s (%d instructions)", s.instExecuted / 1e6, g.name, g.size));
                smallestSelf = g.size;
            }
        }
        if (s.population == 0 && !extinctReported) {
            extinctReported = true;
            logEvent(String.format("%.0fM instructions: the soup went extinct. Press Restart, or Inoculate ancestor.", s.instExecuted / 1e6));
        }
        // status
        statusText = String.format(Locale.ROOT,
                "instructions %,.1fM   %4.1fM/s   creatures %d   memory %d%%   genotypes alive %d   births %,d   deaths %,d   avg size %.1f   cosmic rays %d   copy errors %d   flaws %d",
                s.instExecuted / 1e6, instPerSec / 1e6, s.population, (int) (100.0 * s.usedMemory() / s.soupSize), living.size(),
                s.births, s.deaths, s.avgSize, s.cosmicRays, s.copyMutations, s.flaws);
        // inspector
        int sel = selectedAddr;
        if (sel >= 0) {
            int o = s.owner[sel];
            Soup.Creature c = o >= 0 ? s.byId.get(o) : null;
            if (c != null) inspectText = describe(s, c.genotype, c);
            else inspectText = String.format("Address %d is free memory (left-over code: %s).", sel, Soup.MNEMONIC[s.soup[sel]]);
        } else if (selectedGenotype != null) {
            Soup.Genotype g = null;
            for (Soup.Genotype x : s.genotypes.values()) if (x.name.equals(selectedGenotype)) { g = x; break; }
            if (g != null) inspectText = describe(s, g, null);
        }
        // prune long-dead genotypes occasionally to save memory
        if (s.genotypes.size() > 60000) s.genotypes.values().removeIf(g -> g.alive == 0 && g.born < 5);
    }

    private String describe(Soup s, Soup.Genotype g, Soup.Creature c) {
        StringBuilder sb = new StringBuilder();
        sb.append("Genotype ").append(g.name).append("   (").append(g.size).append(" instructions)\n");
        sb.append(String.format(Locale.ROOT, "Alive: %d   ever born: %d   first seen at %.1fM instructions%n", g.alive, g.born, g.firstSeen / 1e6));
        sb.append(String.format(Locale.ROOT, "Role: %s   (%.0f%% of its instructions executed in others' code)%n", roleOf(g).label, 100 * g.foreignFraction()));
        if (c != null) sb.append(String.format(Locale.ROOT, "This individual: address %d, children %d, errors %d%n", c.start, c.children, c.errors));
        sb.append("\n");
        byte[] code = c != null ? Arrays.copyOfRange(s.soup, c.start, c.start + c.size) : g.genome;
        for (int i = 0; i < code.length; i++) {
            int op = code[i];
            String m = Soup.MNEMONIC[op];
            sb.append(String.format("%3d  %s%n", i, m));
        }
        return sb.toString();
    }

    private void logEvent(String msg) { SwingUtilities.invokeLater(() -> events.add(0, msg)); }

    private void refreshUI() {
        soupView.repaint();
        histogram.repaint();
        sizeTime.repaint();
        tableModel.update(rows);
        status.setText(statusText);
        if (!inspector.getText().equals(inspectText)) { inspector.setText(inspectText); inspector.setCaretPosition(0); }
    }

    // =====================================================================
    //  Actions
    // =====================================================================

    private long readSeed() {
        try { return Long.parseLong(seedField.getText().trim()); }
        catch (NumberFormatException e) { seedField.setText("1"); return 1; }
    }

    private void restart(long seed) {
        tasks.add(() -> {
            soup = newSoup(seed);
            selectedAddr = -1; selectedGenotype = null;
            inspectText = "Click a creature in the soup, or a genotype in the table, to read its code.";
        });
        SwingUtilities.invokeLater(() -> { events.clear(); logEvent("Soup inoculated with the ancestor, 0080aaa (seed " + seed + ")"); });
    }

    private void inoculate(String code, String name, int copies) {
        tasks.add(() -> {
            Soup s = soup;
            byte[] g = Soup.assemble(code);
            int ok = 0;
            for (int i = 0; i < copies; i++) if (s.inject(g, -1) != null) ok++;
            s.recalcRates();
            extinctReported = false;
            logEvent(String.format("%.0fM instructions: %d %s added by hand", s.instExecuted / 1e6, ok, name));
        });
    }

    private void savePng() {
        BufferedImage img = frame;
        if (img == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new File("tierra-soup.png"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".png")) f = new File(f.getPath() + ".png");
        BufferedImage out = new BufferedImage(MAP_W * 4, MAP_H * 4, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(img, 0, 0, MAP_W * 4, MAP_H * 4, null);
        g.dispose();
        try { ImageIO.write(out, "png", f); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage()); }
    }

    private void showAbout() {
        String html = """
            <html><body style='width:520px; font-family:sans-serif'>
            <h2>Tierra</h2>
            <p>Tierra was created by the ecologist <b>Thomas S. Ray</b> in 1990. Instead of watching
            evolution in nature, he built a world where it could happen from scratch: a virtual
            computer whose memory, the <b>soup</b>, is home to small machine-code programs that copy
            themselves.</p>
            <p>The soup starts with one hand-written program of 80 instructions, the <b>ancestor</b>
            (genotype 0080aaa). It finds its own beginning and end, asks for memory for a daughter
            (<tt>mal</tt>), copies itself one instruction at a time (<tt>movii</tt>), and releases the
            daughter as an independent creature (<tt>divide</tt>). Then both do it again.</p>
            <p>Creatures compete for <b>CPU time</b> (each gets a short turn in rotation) and for
            <b>memory</b>. When memory runs out, the <b>reaper</b> kills old creatures and ones that
            make errors. Copies are imperfect: <b>cosmic rays</b> flip bits in memory, <b>copy
            errors</b> corrupt instructions as they're copied, and <b>flaws</b> make instructions
            occasionally give answers that are off by one.</p>
            <p>Programs find their way around using <b>templates</b>: patterns of no-operation
            instructions (<tt>nop0</tt>, <tt>nop1</tt>). "Jump to the nearest complement of 1100"
            works wherever the code ends up, and it works on <i>other creatures' code</i> too.
            Other creatures' memory is write-protected, but anyone may read and run it.</p>
            <p>That's what makes <b>parasites</b> possible. A creature that has lost its own copy
            routine can borrow a neighbour's. Ray found parasites appearing within the first few
            million instructions of his runs, followed by hosts resistant to them, hyper-parasites,
            cooperating creatures and cheaters, and steadily smaller self-replicators.</p>
            <p><b>Colours:</b> green creatures copy themselves unaided; red ones run mostly other
            creatures' code (parasites); amber ones live partly on others. Dim blocks are daughters
            still being written. Click any creature to read its code.</p>
            <p>An independent implementation of Ray's instruction set 0 and the default settings of
            his Tierra 6.02 simulator. The ancestor and the parasite 0045aaa are Ray's own.
            T. S. Ray, "An Approach to the Synthesis of Life", Artificial Life II (1991).</p>
            <p style='color:#666'>Architect: Mario Gianota · Implementation: Claude (Anthropic) · MIT licence<br>
            Tierra: Copyright © Thomas S. Ray</p>
            </body></html>""";
        JOptionPane.showMessageDialog(this, new JLabel(html), "About Tierra Lab", JOptionPane.PLAIN_MESSAGE);
    }

    // =====================================================================
    //  UI
    // =====================================================================

    private void buildUI() {
        JPanel left = new JPanel();
        left.setLayout(new BoxLayout(left, BoxLayout.Y_AXIS));
        left.setBorder(new EmptyBorder(8, 8, 8, 12));

        JPanel run = section("Soup");
        JPanel seedRow = row("Seed: ", seedField);
        JButton restartBtn = new JButton("Restart");
        restartBtn.setToolTipText("Wipe the soup and start again from a single ancestor");
        restartBtn.addActionListener(e -> restart(readSeed()));
        seedRow.add(Box.createHorizontalStrut(6));
        seedRow.add(restartBtn);
        run.add(seedRow);
        speedSlider.onChange(() -> instPerFrame = Math.round(speedSlider.value()) * 1000L);
        run.add(speedSlider);
        JPanel b = new JPanel(new GridLayout(3, 2, 4, 4));
        JButton stepBtn = new JButton("Step");
        JButton ancBtn = new JButton("Add ancestor");
        JButton parBtn = new JButton("Add parasites");
        JButton saveBtn = new JButton("Save PNG");
        JButton aboutBtn = new JButton("About");
        playButton.addActionListener(e -> { running = playButton.isSelected(); playButton.setText(running ? "Pause" : "Play"); });
        stepBtn.addActionListener(e -> singleStep = true);
        ancBtn.setToolTipText("Add one copy of the 80-instruction ancestor, 0080aaa");
        parBtn.setToolTipText("Add five copies of Ray's archetypal parasite, 0045aaa");
        ancBtn.addActionListener(e -> inoculate(Soup.ANCESTOR, "ancestor (0080aaa)", 1));
        parBtn.addActionListener(e -> inoculate(Soup.PARASITE, "parasites (0045aaa)", 5));
        saveBtn.addActionListener(e -> savePng());
        aboutBtn.addActionListener(e -> showAbout());
        b.add(playButton); b.add(stepBtn); b.add(ancBtn); b.add(parBtn); b.add(saveBtn); b.add(aboutBtn);
        b.setAlignmentX(LEFT_ALIGNMENT);
        run.add(Box.createVerticalStrut(4));
        run.add(b);
        JPanel mut = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        mut.add(new JLabel("Mutation: "));
        mut.add(cosmicBox); mut.add(copyBox); mut.add(flawBox);
        mut.setAlignmentX(LEFT_ALIGNMENT);
        run.add(mut);
        JPanel cb = row("Colour by: ", colourBox);
        colourBox.addActionListener(e -> colourBy = (ColourBy) colourBox.getSelectedItem());
        run.add(cb);
        left.add(run);

        JPanel hist = section("Size frequency (creatures of each genome size)");
        histogram.setAlignmentX(LEFT_ALIGNMENT);
        hist.add(histogram);
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (Role r : new Role[]{Role.SELF, Role.MIXED, Role.PARASITE}) {
            JLabel l = new JLabel("■ " + r.label);
            l.setForeground(r.colour.darker());
            l.setFont(l.getFont().deriveFont(11f));
            legend.add(l);
        }
        legend.setAlignmentX(LEFT_ALIGNMENT);
        hist.add(legend);
        left.add(hist);

        JPanel st = section("Sizes over time (one column per million instructions)");
        sizeTime.setAlignmentX(LEFT_ALIGNMENT);
        st.add(sizeTime);
        left.add(st);

        JPanel ev = section("Events");
        JList<String> list = new JList<>(events);
        list.setFont(list.getFont().deriveFont(11f));
        JScrollPane evs = new JScrollPane(list);
        evs.setPreferredSize(new Dimension(380, 110));
        evs.setAlignmentX(LEFT_ALIGNMENT);
        ev.add(evs);
        left.add(ev);
        left.add(Box.createVerticalGlue());

        JScrollPane leftScroll = new JScrollPane(left, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        leftScroll.setBorder(null);
        leftScroll.setPreferredSize(new Dimension(420, 860));
        leftScroll.getVerticalScrollBar().setUnitIncrement(16);

        // right: genotype table and inspector
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setBorder(new EmptyBorder(8, 6, 8, 8));
        table.setFillsViewportHeight(true);
        table.setRowHeight(20);
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                if (!sel && c == 3 && r < tableModel.data.size()) comp.setForeground(tableModel.data.get(r).role().colour.darker());
                else if (!sel) comp.setForeground(Color.BLACK);
                return comp;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0 && r < tableModel.data.size()) { selectedGenotype = tableModel.data.get(r).name(); selectedAddr = -1; }
        });
        JScrollPane ts = new JScrollPane(table);
        ts.setPreferredSize(new Dimension(380, 360));
        ts.setBorder(BorderFactory.createTitledBorder("Living genotypes (most common first)"));
        inspector.setEditable(false);
        inspector.setLineWrap(true);
        inspector.setWrapStyleWord(true);
        inspector.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane is = new JScrollPane(inspector);
        is.setBorder(BorderFactory.createTitledBorder("Inspector"));
        JSplitPane rs = new JSplitPane(JSplitPane.VERTICAL_SPLIT, ts, is);
        rs.setResizeWeight(0.45);
        right.add(rs, BorderLayout.CENTER);
        right.setPreferredSize(new Dimension(400, 860));

        status.setBorder(new EmptyBorder(4, 10, 4, 10));
        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(leftScroll, BorderLayout.WEST);
        getContentPane().add(soupView, BorderLayout.CENTER);
        getContentPane().add(right, BorderLayout.EAST);
        getContentPane().add(status, BorderLayout.SOUTH);
    }

    private static JPanel row(String label, JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        p.add(new JLabel(label));
        p.add(c);
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    private static JPanel section(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(), title, TitledBorder.LEFT, TitledBorder.TOP),
                new EmptyBorder(4, 6, 6, 6)));
        p.setAlignmentX(LEFT_ALIGNMENT);
        return p;
    }

    // =====================================================================
    //  Widgets
    // =====================================================================

    static final class ParamSlider extends JPanel {
        private static final int TICKS = 10000;
        private final double min, max;
        private final String fmt;
        private final JSlider slider = new JSlider(0, TICKS);
        private final JLabel valueLabel = new JLabel();
        private Runnable listener = () -> { };
        ParamSlider(String name, double min, double max, double initial, String fmt) {
            this.min = min; this.max = max; this.fmt = fmt;
            setLayout(new BorderLayout());
            setAlignmentX(LEFT_ALIGNMENT);
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));
            JPanel top = new JPanel(new BorderLayout());
            JLabel nameLabel = new JLabel(name);
            nameLabel.setFont(nameLabel.getFont().deriveFont(12f));
            valueLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            top.add(nameLabel, BorderLayout.WEST);
            top.add(valueLabel, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            add(slider, BorderLayout.CENTER);
            slider.addChangeListener(e -> { refresh(); listener.run(); });
            setValue(initial);
        }
        double value() { return min + (max - min) * slider.getValue() / TICKS; }
        void setValue(double v) { slider.setValue((int) Math.round((v - min) / (max - min) * TICKS)); refresh(); }
        void onChange(Runnable r) { listener = r; }
        private void refresh() { valueLabel.setText(String.format(Locale.ROOT, fmt, value())); }
    }

    final class SoupView extends JPanel {
        SoupView() {
            setPreferredSize(new Dimension(780, 540));
            setBackground(new Color(0x0a, 0x0a, 0x10));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    Rectangle r = area();
                    if (!r.contains(e.getPoint())) return;
                    int x = (int) ((e.getX() - r.x) * (double) MAP_W / r.width), y = (int) ((e.getY() - r.y) * (double) MAP_H / r.height);
                    selectedAddr = Math.min(MAP_W * MAP_H - 1, y * MAP_W + x);
                    selectedGenotype = null;
                    table.clearSelection();
                }
            });
            setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
            setToolTipText("Each pixel is one instruction of memory, read left to right, top to bottom. Click a creature to read its code.");
        }
        Rectangle area() {
            double aspect = (double) MAP_W / MAP_H;
            int W = getWidth() - 16, H = getHeight() - 16;
            int w = W, h = (int) (W / aspect);
            if (h > H) { h = H; w = (int) (H * aspect); }
            return new Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            BufferedImage img = frame;
            if (img == null) return;
            Rectangle r = area();
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img, r.x, r.y, r.width, r.height, null);
        }
    }

    final class SizeHistogram extends JPanel {
        SizeHistogram() {
            setPreferredSize(new Dimension(380, 130));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));
            setBackground(new Color(0x16, 0x16, 0x1e));
        }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            int[] h = sizeHist;
            Role[] rr = sizeRole;
            int w = getWidth(), ht = getHeight() - 16;
            int max = 1;
            for (int v : h) max = Math.max(max, v);
            int maxSize = 120;
            double bw = (w - 8) / (double) maxSize;
            for (int s = 0; s <= maxSize; s++) {
                if (h[s] == 0) continue;
                int bh = (int) Math.max(1, (double) h[s] / max * (ht - 6));
                g.setColor(rr[s] == null ? Color.GRAY : rr[s].colour);
                g.fillRect((int) (4 + s * bw), ht - bh, Math.max(1, (int) Math.ceil(bw)), bh);
            }
            g.setColor(new Color(170, 170, 190));
            g.setFont(g.getFont().deriveFont(10f));
            for (int s = 0; s <= maxSize; s += 20) g.drawString(String.valueOf(s), (int) (4 + s * bw) - 4, getHeight() - 3);
            g.drawString("most common: " + max, w - 90, 12);
        }
    }

    /** Size (vertical) against time (horizontal); brightness = number of creatures of that size. */
    final class SizeTimeChart extends JPanel {
        SizeTimeChart() {
            setPreferredSize(new Dimension(380, 150));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
            setBackground(new Color(0x0c, 0x0c, 0x12));
        }
        @Override protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            List<int[]> h;
            List<Long> tt;
            synchronized (histLock) { h = new ArrayList<>(sizeHistory); tt = new ArrayList<>(sizeHistoryTime); }
            int w = getWidth(), ht = getHeight() - 14;
            if (h.isEmpty()) { g.setColor(Color.GRAY); g.drawString("waiting…", 8, ht / 2); return; }
            int maxSize = 120;
            int cols = h.size();
            BufferedImage img = new BufferedImage(cols, maxSize + 1, BufferedImage.TYPE_INT_RGB);
            for (int c = 0; c < cols; c++) {
                int[] counts = h.get(c);
                for (int s = 0; s <= maxSize; s++) {
                    int v = counts[s];
                    double t = v == 0 ? 0 : Math.min(1, 0.25 + Math.log1p(v) / Math.log1p(150));
                    int col = Color.HSBtoRGB((float) Math.max(0, Math.min(0.8, (s - 15) / 110.0 * 0.8)), 0.75f, (float) t);
                    img.setRGB(c, maxSize - s, v == 0 ? 0x0c0c12 : col);
                }
            }
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(img, 26, 2, w - 30, ht - 2, null);
            g.setColor(new Color(170, 170, 190));
            g.setFont(g.getFont().deriveFont(10f));
            for (int s = 0; s <= maxSize; s += 40) g.drawString(String.valueOf(s), 2, 2 + (int) ((maxSize - s) / (double) maxSize * (ht - 4)) + 4);
            g.drawString(String.format(Locale.ROOT, "time → %.0fM instructions", tt.get(tt.size() - 1) / 1e6), 30, getHeight() - 2);
        }
    }

    final class GenotypeModel extends AbstractTableModel {
        List<Row> data = List.of();
        final String[] cols = {"Genotype", "Size", "Alive", "Role", "Foreign %", "First seen"};
        void update(List<Row> r) {
            String sel = selectedGenotype;
            data = r;
            fireTableDataChanged();
            if (sel != null) for (int i = 0; i < data.size(); i++) if (data.get(i).name().equals(sel)) { table.getSelectionModel().setSelectionInterval(i, i); break; }
        }
        @Override public int getRowCount() { return data.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Row x = data.get(r);
            return switch (c) {
                case 0 -> x.name();
                case 1 -> x.size();
                case 2 -> x.alive();
                case 3 -> x.role().label;
                case 4 -> String.format("%.0f", 100 * x.foreign());
                default -> String.format(Locale.ROOT, "%.1fM", x.firstSeen() / 1e6);
            };
        }
    }
}
