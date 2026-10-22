package tv.debloat.home;

import android.content.Context;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/**
 * A small client for the TV's own adb (the developer connection), over TCP to 127.0.0.1:5555. It lets the home
 * screen run the few shell commands it needs (turn remote buttons back on after a restart, switch the Home button
 * between Debloat TV and the TV's own home screen). The TV asks once, "Allow USB debugging?", for this app's key.
 *
 * Protocol: messages with a 24-byte little-endian header (command, arg0, arg1, data length, data checksum, magic);
 * A_CNXN to connect, A_AUTH with an RSA key (sign the device's token, or send the public key for approval), A_OPEN a
 * "shell:" stream, then A_WRTE/A_OKAY until A_CLSE.
 */
final class Adb implements Closeable {   // our own client, written from AOSP's protocol notes
    // Command words, as named in AOSP's adb protocol notes (packages/modules/adb/protocol.txt): the ASCII letters,
    // read as a little-endian int.
    private static final int A_CNXN = word("CNXN"), A_AUTH = word("AUTH"), A_OPEN = word("OPEN"), A_OKAY = word("OKAY"),
            A_CLSE = word("CLSE"), A_WRTE = word("WRTE");
    private static final int A_VERSION = 0x01000001, MAX_PAYLOAD = 256 * 1024;
    private static final int TOKEN = 1, SIGNATURE = 2, PUBLIC_KEY = 3;   // A_AUTH types

    private static int word(String four) {
        byte[] b = four.getBytes(StandardCharsets.US_ASCII);
        return (b[0] & 0xff) | (b[1] & 0xff) << 8 | (b[2] & 0xff) << 16 | (b[3] & 0xff) << 24;
    }
    /** PKCS#1 DigestInfo for SHA-1: adb signs its 20-byte token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private Adb(Socket s) throws IOException {
        socket = s;
        in = s.getInputStream();
        out = s.getOutputStream();
    }

    /** Is the TV's adb listening here (USB debugging on, port 5555)? */
    static boolean available() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", 5555), 800);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Connects and signs in. The first time, the TV shows "Allow USB debugging?": this waits up to `waitMs` for it
     * to be accepted. Throws if adb is off or the prompt is declined.
     */
    static Adb connect(Context c, int waitMs) throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", 5555), 2000);
        s.setSoTimeout(waitMs);
        Adb adb = new Adb(s);
        try {
            adb.handshake(c);
            s.setSoTimeout(15000);
            return adb;
        } catch (IOException e) {
            adb.close();
            throw e;
        }
    }

    private void handshake(Context c) throws IOException {
        KeyPair key = key(c);
        write(A_CNXN, A_VERSION, MAX_PAYLOAD, "host::debloat-tv\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        while (true) {
            Packet m = next();
            if (m.command == A_CNXN) return;   // connected
            if (m.command != A_AUTH || m.arg0 != TOKEN) throw new IOException("unexpected adb answer");
            if (!signed) {
                write(A_AUTH, SIGNATURE, 0, sign(key.getPrivate(), m.data));
                signed = true;
            } else {
                // Our key isn't known yet: send it, and the TV asks "Allow USB debugging?".
                write(A_AUTH, PUBLIC_KEY, 0, publicKey((RSAPublicKey) key.getPublic()));
            }
        }
    }

    /** Runs a shell command and returns what it printed. */
    String run(String command) throws IOException {
        int local = nextId++;
        write(A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Packet m = next();
            if (m.arg1 != local) continue;   // another stream (shouldn't happen: one at a time)
            if (m.command == A_OKAY) {
                remote = m.arg0;
            } else if (m.command == A_WRTE) {
                printed.write(m.data);
                write(A_OKAY, local, m.arg0, new byte[0]);
            } else if (m.command == A_CLSE) {
                if (remote != 0) write(A_CLSE, local, remote, new byte[0]);
                return printed.toString("UTF-8");
            }
        }
    }

    @Override public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    // MARK: - Messages

    private static final class Packet {
        int command, arg0, arg1;
        byte[] data;
    }

    private void write(int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        int sum = 0;
        for (byte x : data) sum += x & 0xff;
        b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command).put(data);
        out.write(b.array());
        out.flush();
    }

    private Packet next() throws IOException {
        DataInputStream d = new DataInputStream(in);
        byte[] header = new byte[24];
        d.readFully(header);
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        Packet m = new Packet();
        m.command = b.getInt();
        m.arg0 = b.getInt();
        m.arg1 = b.getInt();
        int length = b.getInt();
        if (length < 0 || length > MAX_PAYLOAD * 4) throw new IOException("bad adb message");
        m.data = new byte[length];
        d.readFully(m.data);
        return m;
    }

    // MARK: - Our key (made once, kept in the app's private files)

    private static synchronized KeyPair key(Context c) throws IOException {
        File priv = new File(c.getFilesDir(), "adb.key"), pub = new File(c.getFilesDir(), "adb.pub");
        try {
            KeyFactory kf = KeyFactory.getInstance("RSA");
            if (priv.exists() && pub.exists()) {
                return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                        kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
            }
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            KeyPair k = g.generateKeyPair();
            try (FileOutputStream o = new FileOutputStream(priv)) { o.write(k.getPrivate().getEncoded()); }
            try (FileOutputStream o = new FileOutputStream(pub)) { o.write(k.getPublic().getEncoded()); }
            return k;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("key: " + e.getMessage());
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (FileInputStream i = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int n = 0;
            while (n < b.length) {
                int r = i.read(b, n, b.length - n);
                if (r < 0) break;
                n += r;
            }
            return b;
        }
    }

    private static byte[] sign(PrivateKey key, byte[] token) throws IOException {
        try {
            byte[] digestInfo = new byte[SHA1_PREFIX.length + token.length];
            System.arraycopy(SHA1_PREFIX, 0, digestInfo, 0, SHA1_PREFIX.length);
            System.arraycopy(token, 0, digestInfo, SHA1_PREFIX.length, token.length);
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);   // with a private key: PKCS#1 v1.5 signature padding
            return cipher.doFinal(digestInfo);
        } catch (Exception e) {
            throw new IOException("sign: " + e.getMessage());
        }
    }

    /**
     * The public key in adb's own format: len, n0inv, n and R² mod n as little-endian 32-bit words, exponent; base64,
     * then " name" and a NUL.
     */
    private static byte[] publicKey(RSAPublicKey key) {
        int words = 2048 / 32;
        BigInteger n = key.getModulus(), r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.valueOf(2), n);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(key.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " debloat-tv@tv\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
