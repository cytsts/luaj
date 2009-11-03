/*******************************************************************************
* Copyright (c) 2009 Luaj.org. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
* 
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
******************************************************************************/
package org.luaj.vm2;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Hashtable;

/*
** Loader to load compiled function prototypes
*/
public class LoadState {
	
	/** format corresponding to non-number-patched lua, all numbers are floats or doubles */
	public static final int NUMBER_FORMAT_FLOATS_OR_DOUBLES    = 0;

	/** format corresponding to non-number-patched lua, all numbers are ints */
	public static final int NUMBER_FORMAT_INTS_ONLY            = 1;
	
	/** format corresponding to number-patched lua, all numbers are 32-bit (4 byte) ints */
	public static final int NUMBER_FORMAT_NUM_PATCH_INT32      = 4;
	
	// type constants	
	public static final int LUA_TINT            = (-2);
	public static final int LUA_TNONE			= (-1);
	public static final int LUA_TNIL			= 0;
	public static final int LUA_TBOOLEAN		= 1;
	public static final int LUA_TLIGHTUSERDATA	= 2;
	public static final int LUA_TNUMBER			= 3;
	public static final int LUA_TSTRING			= 4;
	public static final int LUA_TTABLE			= 5;
	public static final int LUA_TFUNCTION		= 6;
	public static final int LUA_TUSERDATA		= 7;
	public static final int LUA_TTHREAD			= 8;
	public static final int LUA_TVALUE          = 9;
	
	/** Interface for the compiler, if it is installed. */
	public interface LuaCompiler {
		
		/** Compile into a prototype, without taking the additional step of create a LuaFunction or LuaClosure */
		public Prototype compile(int firstByte, InputStream stream, String name) throws IOException;
		
		/** Load into a Closure or LuaFunction, with the supplied initial environment */
		public LuaFunction load(int firstByte, InputStream stream, String name, LuaValue env) throws IOException;
		
		/** Load into a LuaFunction given a prototype.  May compile into a class, or return a LuaClosure 
		 * @param filename TODO*/
		public LuaFunction load(Prototype p, String filename, LuaValue env);
	}

	/** Compiler instance, if installed */
	public static LuaCompiler compiler = null;

	/** Signature byte indicating the file is a compiled binary chunk */
	private static final byte[] LUA_SIGNATURE	= { '\033', 'L', 'u', 'a' };

	/** Name for compiled chunks */
	public static final String SOURCE_BINARY_STRING = "binary string";


	/** for header of binary files -- this is Lua 5.1 */
	public static final int LUAC_VERSION		= 0x51;

	/** for header of binary files -- this is the official format */
	public static final int LUAC_FORMAT		= 0;

	/** size of header of binary files */
	public static final int LUAC_HEADERSIZE		= 12;

	// values read from the header
	private int     luacVersion;
	private int     luacFormat;
	private boolean luacLittleEndian;
	private int     luacSizeofInt;
	private int     luacSizeofSizeT;
	private int     luacSizeofInstruction;
	private int     luacSizeofLuaNumber;
	private int 	luacNumberFormat;

	/** input stream from which we are loading */
	public final DataInputStream is;

	/** Name of what is being loaded? */
	String name;

	private static final LuaValue[]     NOVALUES    = {};
	private static final Prototype[] NOPROTOS    = {};
	private static final LocVars[]   NOLOCVARS   = {};
	private static final LuaString[]  NOSTRVALUES = {};
	private static final int[]       NOINTS      = {};
	
	/** Read buffer */
	private byte[] buf = new byte[512];
	
	
	int loadInt() throws IOException {
		is.readFully(buf,0,4);
		return luacLittleEndian? 
				(buf[3] << 24) | ((0xff & buf[2]) << 16) | ((0xff & buf[1]) << 8) | (0xff & buf[0]):
				(buf[0] << 24) | ((0xff & buf[1]) << 16) | ((0xff & buf[2]) << 8) | (0xff & buf[3]);
	}
	
	int[] loadIntArray() throws IOException {
		int n = loadInt();
		if ( n == 0 )
			return NOINTS;
		
		// read all data at once
		int m = n << 2;
		if ( buf.length < m )
			buf = new byte[m];
		is.readFully(buf,0,m);
		int[] array = new int[n];
		for ( int i=0, j=0; i<n; ++i, j+=4 )
			array[i] = luacLittleEndian? 
					(buf[j+3] << 24) | ((0xff & buf[j+2]) << 16) | ((0xff & buf[j+1]) << 8) | (0xff & buf[j+0]):
					(buf[j+0] << 24) | ((0xff & buf[j+1]) << 16) | ((0xff & buf[j+2]) << 8) | (0xff & buf[j+3]);

		return array;
	}
	

	long loadInt64() throws IOException {
		int a,b;
		if ( this.luacLittleEndian ) {
			a = loadInt();
			b = loadInt();
		} else {
			b = loadInt();
			a = loadInt();
		}
		return (((long)b)<<32) | (((long)a)&0xffffffffL);
	}

	LuaString loadString() throws IOException {
		int size = loadInt();
		if ( size == 0 )
			return null;
		byte[] bytes = new byte[size];
		is.readFully( bytes, 0, size );
		return new LuaString( bytes, 0, bytes.length - 1 );
	}
	
	public static LuaValue longBitsToLuaNumber( long bits ) {
		if ( ( bits & ( ( 1L << 63 ) - 1 ) ) == 0L ) {
			return LuaValue.ZERO;
		}
		
		int e = (int)((bits >> 52) & 0x7ffL) - 1023;
		
		if ( e >= 0 && e < 31 ) {
			long f = bits & 0xFFFFFFFFFFFFFL;
			int shift = 52 - e;
			long intPrecMask = ( 1L << shift ) - 1;
			if ( ( f & intPrecMask ) == 0 ) {
				int intValue = (int)( f >> shift ) | ( 1 << e );
				return LuaInteger.valueOf( ( ( bits >> 63 ) != 0 ) ? -intValue : intValue );
			}
		}
		
		return LuaValue.valueOf( Double.longBitsToDouble(bits) );
	}
	
	LuaValue loadNumber() throws IOException {
		if ( luacNumberFormat == NUMBER_FORMAT_INTS_ONLY ) {
			return LuaInteger.valueOf( loadInt() );
		} else {
			return longBitsToLuaNumber( loadInt64() );
		}
	}

	void loadConstants(Prototype f) throws IOException {
		int n = loadInt();
		LuaValue[] values = n>0? new LuaValue[n]: NOVALUES;
		for ( int i=0; i<n; i++ ) {
			switch ( is.readByte() ) {
			case LUA_TNIL:
				values[i] = LuaValue.NIL;
				break;
			case LUA_TBOOLEAN:
				values[i] = (0 != is.readUnsignedByte()? LuaValue.TRUE: LuaValue.FALSE);
				break;
			case LUA_TINT:
				values[i] = LuaInteger.valueOf( loadInt() );
				break;
			case LUA_TNUMBER:
				values[i] = loadNumber();
				break;
			case LUA_TSTRING:
				values[i] = loadString();
				break;
			default:
				throw new IllegalStateException("bad constant");
			}
		}
		f.k = values;
		
		n = loadInt();
		Prototype[] protos = n>0? new Prototype[n]: NOPROTOS;
		for ( int i=0; i<n; i++ )
			protos[i] = loadFunction(f.source);
		f.p = protos;
	}

	void loadDebug( Prototype f ) throws IOException {
		f.lineinfo = loadIntArray();
		int n = loadInt();
		f.locvars = n>0? new LocVars[n]: NOLOCVARS;
		for ( int i=0; i<n; i++ ) {
			LuaString varname = loadString();
			int startpc = loadInt();
			int endpc = loadInt();
			f.locvars[i] = new LocVars(varname, startpc, endpc);
		}
		
		n = loadInt();
		f.upvalues = n>0? new LuaString[n]: NOSTRVALUES;
		for ( int i=0; i<n; i++ ) {
			f.upvalues[i] = loadString();
		}
	}

	public Prototype loadFunction(LuaString p) throws IOException {
		Prototype f = new Prototype();
//		this.L.push(f);
		f.source = loadString();
		if ( f.source == null )
			f.source = p;
		f.linedefined = loadInt();
		f.lastlinedefined = loadInt();
		f.nups = is.readUnsignedByte();
		f.numparams = is.readUnsignedByte();
		f.is_vararg = is.readUnsignedByte();
		f.maxstacksize = is.readUnsignedByte();
		f.code = loadIntArray();
		loadConstants(f);
		loadDebug(f);
		
		// TODO: add check here, for debugging purposes, I believe
		// see ldebug.c
//		 IF (!luaG_checkcode(f), "bad code");
		
//		 this.L.pop();
		 return f;
	}

	public void loadHeader() throws IOException {
		luacVersion = is.readByte();
		luacFormat = is.readByte();
		luacLittleEndian = (0 != is.readByte());
		luacSizeofInt = is.readByte();
		luacSizeofSizeT = is.readByte();
		luacSizeofInstruction = is.readByte();
		luacSizeofLuaNumber = is.readByte();
		luacNumberFormat = is.readByte();
	}
	
	public static LuaFunction load( InputStream stream, String name, LuaValue env ) throws IOException {
		Prototype p = compile( stream, name );
		if ( compiler != null )
			return compiler.load(p, name, env);
		else
			return new LuaClosure( p, env );
	}

	public static Prototype compile( InputStream stream, String name ) throws IOException {
		int c = stream.read();
		if ( c != LUA_SIGNATURE[0] ) {
			if ( compiler != null )
				return compiler.compile(c, stream, name);
			throw new LuaError("no compiler");
		}

		// check rest of signature
		for ( int i=1; i<4; i++ ) {
			if ( stream.read() != LUA_SIGNATURE[i] )
				throw new IllegalArgumentException("bad signature");
		}
		
		// load file as a compiled chunk
		String sname = getSourceName(name);
		LoadState s = new LoadState( stream, sname );
		s.loadHeader();

		// check format
		switch ( s.luacNumberFormat ) {
		case NUMBER_FORMAT_FLOATS_OR_DOUBLES:
		case NUMBER_FORMAT_INTS_ONLY:
		case NUMBER_FORMAT_NUM_PATCH_INT32:
			break;
		default:
			throw new LuaError("unsupported int size");
		}
		return s.loadFunction( LuaString.valueOf(sname) );
	}
	
    public static String getSourceName(String name) {
        String sname = name;
        if ( name.startsWith("@") || name.startsWith("=") )
			sname = name.substring(1);
		else if ( name.startsWith("\033") )
			sname = SOURCE_BINARY_STRING;
        return sname;
    }

	/** Private constructor for create a load state */
	private LoadState( InputStream stream, String name ) {
		this.name = name;
		this.is = new DataInputStream( stream );
	}
}
