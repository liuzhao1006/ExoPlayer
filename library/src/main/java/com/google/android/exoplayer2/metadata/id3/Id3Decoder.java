/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer2.metadata.id3;

import android.util.Log;
import com.google.android.exoplayer2.metadata.Metadata;
import com.google.android.exoplayer2.metadata.MetadataDecoder;
import com.google.android.exoplayer2.metadata.MetadataDecoderException;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.ParsableByteArray;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Decodes individual TXXX text frames from raw ID3 data.
 */
public final class Id3Decoder implements MetadataDecoder {

  private static final String TAG = "Id3Decoder";

  private static final int ID3_TEXT_ENCODING_ISO_8859_1 = 0;
  private static final int ID3_TEXT_ENCODING_UTF_16 = 1;
  private static final int ID3_TEXT_ENCODING_UTF_16BE = 2;
  private static final int ID3_TEXT_ENCODING_UTF_8 = 3;

  @Override
  public boolean canDecode(String mimeType) {
    return mimeType.equals(MimeTypes.APPLICATION_ID3);
  }

  @Override
  public Metadata decode(byte[] data, int size) throws MetadataDecoderException {
    List<Id3Frame> id3Frames = new ArrayList<>();
    ParsableByteArray id3Data = new ParsableByteArray(data, size);

    Id3Header id3Header = decodeHeader(id3Data);
    if (id3Header == null) {
      return null;
    }

    int startPosition = id3Data.getPosition();
    int framesSize = id3Header.framesSize;
    if (id3Header.isUnsynchronized) {
      framesSize = removeUnsynchronization(id3Data, id3Header.framesSize);
    }
    id3Data.setLimit(startPosition + framesSize);

    int frameHeaderSize = id3Header.majorVersion == 2 ? 6 : 10;
    while (id3Data.bytesLeft() >= frameHeaderSize) {
      Id3Frame frame = decodeFrame(id3Header, id3Data);
      if (frame != null) {
        id3Frames.add(frame);
      }
    }

    return new Metadata(id3Frames);
  }

  /**
   * @param data A {@link ParsableByteArray} from which the header should be read.
   * @return The parsed header, or null if the ID3 tag is unsupported.
   * @throws MetadataDecoderException If the first three bytes differ from "ID3".
   */
  private static Id3Header decodeHeader(ParsableByteArray data)
      throws MetadataDecoderException {
    int id1 = data.readUnsignedByte();
    int id2 = data.readUnsignedByte();
    int id3 = data.readUnsignedByte();
    if (id1 != 'I' || id2 != 'D' || id3 != '3') {
      throw new MetadataDecoderException(String.format(Locale.US,
          "Unexpected ID3 tag identifier, expected \"ID3\", actual \"%c%c%c\".", id1, id2, id3));
    }

    int majorVersion = data.readUnsignedByte();
    data.skipBytes(1); // Skip minor version.
    int flags = data.readUnsignedByte();
    int framesSize = data.readSynchSafeInt();

    if (majorVersion == 2) {
      boolean isCompressed = (flags & 0x40) != 0;
      if (isCompressed) {
        Log.w(TAG, "Skipped ID3 tag with majorVersion=1 and undefined compression scheme");
        return null;
      }
    } else if (majorVersion == 3) {
      boolean hasExtendedHeader = (flags & 0x40) != 0;
      if (hasExtendedHeader) {
        int extendedHeaderSize = data.readInt(); // Size excluding size field.
        data.skipBytes(extendedHeaderSize);
        framesSize -= (extendedHeaderSize + 4);
      }
    } else if (majorVersion == 4) {
      boolean hasExtendedHeader = (flags & 0x40) != 0;
      if (hasExtendedHeader) {
        int extendedHeaderSize = data.readSynchSafeInt(); // Size including size field.
        data.skipBytes(extendedHeaderSize - 4);
        framesSize -= extendedHeaderSize;
      }
      boolean hasFooter = (flags & 0x10) != 0;
      if (hasFooter) {
        framesSize -= 10;
      }
    } else {
      Log.w(TAG, "Skipped ID3 tag with unsupported majorVersion=" + majorVersion);
      return null;
    }

    // isUnsynchronized is advisory only in version 4. Frame level flags are used instead.
    boolean isUnsynchronized = majorVersion < 4 && (flags & 0x80) != 0;
    return new Id3Header(majorVersion, isUnsynchronized, framesSize);
  }

  private Id3Frame decodeFrame(Id3Header id3Header, ParsableByteArray id3Data)
      throws MetadataDecoderException {
    int frameId0 = id3Data.readUnsignedByte();
    int frameId1 = id3Data.readUnsignedByte();
    int frameId2 = id3Data.readUnsignedByte();
    int frameId3 = id3Header.majorVersion >= 3 ? id3Data.readUnsignedByte() : 0;

    int frameSize;
    if (id3Header.majorVersion == 4) {
      frameSize = id3Data.readUnsignedIntToInt();
      if ((frameSize & 0x808080L) == 0) {
        // Parse the frame size as a syncsafe integer, as per the spec.
        frameSize = (frameSize & 0xFF) | (((frameSize >> 8) & 0xFF) << 7)
            | (((frameSize >> 16) & 0xFF) << 14) | (((frameSize >> 24) & 0xFF) << 21);
      } else {
        // Proceed using the frame size read as an unsigned integer.
        Log.w(TAG, "Frame size not specified as syncsafe integer");
      }
    } else if (id3Header.majorVersion == 3) {
      frameSize = id3Data.readUnsignedIntToInt();
    } else /* id3Header.majorVersion == 2 */ {
      frameSize = id3Data.readUnsignedInt24();
    }

    int flags = id3Header.majorVersion >= 2 ? id3Data.readShort() : 0;
    if (frameId0 == 0 && frameId1 == 0 && frameId2 == 0 && frameId3 == 0 && frameSize == 0
        && flags == 0) {
      // We must be reading zero padding at the end of the tag.
      id3Data.setPosition(id3Data.limit());
      return null;
    }

    int nextFramePosition = id3Data.getPosition() + frameSize;

    // Frame flags.
    boolean isCompressed = false;
    boolean isEncrypted = false;
    boolean isUnsynchronized = false;
    boolean hasDataLength = false;
    boolean hasGroupIdentifier = false;
    if (id3Header.majorVersion == 3) {
      isCompressed = (flags & 0x0080) != 0;
      isEncrypted = (flags & 0x0040) != 0;
      hasGroupIdentifier = (flags & 0x0020) != 0;
      hasDataLength = isCompressed;
    } else if (id3Header.majorVersion == 4) {
      hasGroupIdentifier = (flags & 0x0040) != 0;
      isCompressed = (flags & 0x0008) != 0;
      isEncrypted = (flags & 0x0004) != 0;
      isUnsynchronized = (flags & 0x0002) != 0;
      hasDataLength = (flags & 0x0001) != 0;
    }

    if (isCompressed || isEncrypted) {
      Log.w(TAG, "Skipping unsupported compressed or encrypted frame");
      id3Data.setPosition(nextFramePosition);
      return null;
    }

    if (hasGroupIdentifier) {
      frameSize--;
      id3Data.skipBytes(1);
    }
    if (hasDataLength) {
      frameSize -= 4;
      id3Data.skipBytes(4);
    }
    if (isUnsynchronized) {
      frameSize = removeUnsynchronization(id3Data, frameSize);
    }

    try {
      Id3Frame frame;
      if (frameId0 == 'T' && frameId1 == 'X' && frameId2 == 'X' && frameId3 == 'X') {
        frame = decodeTxxxFrame(id3Data, frameSize);
      } else if (frameId0 == 'P' && frameId1 == 'R' && frameId2 == 'I' && frameId3 == 'V') {
        frame = decodePrivFrame(id3Data, frameSize);
      } else if (frameId0 == 'G' && frameId1 == 'E' && frameId2 == 'O' && frameId3 == 'B') {
        frame = decodeGeobFrame(id3Data, frameSize);
      } else if (frameId0 == 'A' && frameId1 == 'P' && frameId2 == 'I' && frameId3 == 'C') {
        frame = decodeApicFrame(id3Data, frameSize);
      } else if (frameId0 == 'T') {
        String id = frameId3 != 0 ?
            String.format(Locale.US, "%c%c%c%c", frameId0, frameId1, frameId2, frameId3) :
            String.format(Locale.US, "%c%c%c", frameId0, frameId1, frameId2);
        frame = decodeTextInformationFrame(id3Data, frameSize, id);
      } else if (frameId0 == 'C' && frameId1 == 'O' && frameId2 == 'M' &&
          (frameId3 == 'M' || frameId3 == 0)) {
        frame = decodeCommentFrame(id3Data, frameSize);
      } else {
        String id = frameId3 != 0 ?
            String.format(Locale.US, "%c%c%c%c", frameId0, frameId1, frameId2, frameId3) :
            String.format(Locale.US, "%c%c%c", frameId0, frameId1, frameId2);
        frame = decodeBinaryFrame(id3Data, frameSize, id);
      }
      return frame;
    } catch (UnsupportedEncodingException e) {
      throw new MetadataDecoderException("Unsupported character encoding");
    } finally {
      id3Data.setPosition(nextFramePosition);
    }
  }

  private static TxxxFrame decodeTxxxFrame(ParsableByteArray id3Data, int frameSize)
      throws UnsupportedEncodingException {
    int encoding = id3Data.readUnsignedByte();
    String charset = getCharsetName(encoding);

    byte[] data = new byte[frameSize - 1];
    id3Data.readBytes(data, 0, frameSize - 1);

    int descriptionEndIndex = indexOfEos(data, 0, encoding);
    String description = new String(data, 0, descriptionEndIndex, charset);

    int valueStartIndex = descriptionEndIndex + delimiterLength(encoding);
    int valueEndIndex = indexOfEos(data, valueStartIndex, encoding);
    String value = new String(data, valueStartIndex, valueEndIndex - valueStartIndex, charset);

    return new TxxxFrame(description, value);
  }

  private static PrivFrame decodePrivFrame(ParsableByteArray id3Data, int frameSize)
      throws UnsupportedEncodingException {
    byte[] data = new byte[frameSize];
    id3Data.readBytes(data, 0, frameSize);

    int ownerEndIndex = indexOfZeroByte(data, 0);
    String owner = new String(data, 0, ownerEndIndex, "ISO-8859-1");

    int privateDataStartIndex = ownerEndIndex + 1;
    byte[] privateData = Arrays.copyOfRange(data, privateDataStartIndex, data.length);

    return new PrivFrame(owner, privateData);
  }

  private static GeobFrame decodeGeobFrame(ParsableByteArray id3Data, int frameSize)
      throws UnsupportedEncodingException {
    int encoding = id3Data.readUnsignedByte();
    String charset = getCharsetName(encoding);

    byte[] data = new byte[frameSize - 1];
    id3Data.readBytes(data, 0, frameSize - 1);

    int mimeTypeEndIndex = indexOfZeroByte(data, 0);
    String mimeType = new String(data, 0, mimeTypeEndIndex, "ISO-8859-1");

    int filenameStartIndex = mimeTypeEndIndex + 1;
    int filenameEndIndex = indexOfEos(data, filenameStartIndex, encoding);
    String filename = new String(data, filenameStartIndex, filenameEndIndex - filenameStartIndex,
        charset);

    int descriptionStartIndex = filenameEndIndex + delimiterLength(encoding);
    int descriptionEndIndex = indexOfEos(data, descriptionStartIndex, encoding);
    String description = new String(data, descriptionStartIndex,
        descriptionEndIndex - descriptionStartIndex, charset);

    int objectDataStartIndex = descriptionEndIndex + delimiterLength(encoding);
    byte[] objectData = Arrays.copyOfRange(data, objectDataStartIndex, data.length);

    return new GeobFrame(mimeType, filename, description, objectData);
  }

  private static ApicFrame decodeApicFrame(ParsableByteArray id3Data, int frameSize)
      throws UnsupportedEncodingException {
    int encoding = id3Data.readUnsignedByte();
    String charset = getCharsetName(encoding);

    byte[] data = new byte[frameSize - 1];
    id3Data.readBytes(data, 0, frameSize - 1);

    int mimeTypeEndIndex = indexOfZeroByte(data, 0);
    String mimeType = new String(data, 0, mimeTypeEndIndex, "ISO-8859-1");

    int pictureType = data[mimeTypeEndIndex + 1] & 0xFF;

    int descriptionStartIndex = mimeTypeEndIndex + 2;
    int descriptionEndIndex = indexOfEos(data, descriptionStartIndex, encoding);
    String description = new String(data, descriptionStartIndex,
        descriptionEndIndex - descriptionStartIndex, charset);

    int pictureDataStartIndex = descriptionEndIndex + delimiterLength(encoding);
    byte[] pictureData = Arrays.copyOfRange(data, pictureDataStartIndex, data.length);

    return new ApicFrame(mimeType, description, pictureType, pictureData);
  }

  private static TextInformationFrame decodeTextInformationFrame(ParsableByteArray id3Data,
      int frameSize, String id) throws UnsupportedEncodingException {
    int encoding = id3Data.readUnsignedByte();
    String charset = getCharsetName(encoding);

    byte[] data = new byte[frameSize - 1];
    id3Data.readBytes(data, 0, frameSize - 1);

    int descriptionEndIndex = indexOfEos(data, 0, encoding);
    String description = new String(data, 0, descriptionEndIndex, charset);

    return new TextInformationFrame(id, description);
  }

  private static CommentFrame decodeCommentFrame(ParsableByteArray id3Data, int frameSize)
      throws UnsupportedEncodingException {
    int encoding = id3Data.readUnsignedByte();
    String charset = getCharsetName(encoding);

    byte[] data = new byte[3];
    id3Data.readBytes(data, 0, 3);
    String language = new String(data, 0, 3);

    data = new byte[frameSize - 4];
    id3Data.readBytes(data, 0, frameSize - 4);

    int descriptionEndIndex = indexOfEos(data, 0, encoding);
    String description = new String(data, 0, descriptionEndIndex, charset);

    int textStartIndex = descriptionEndIndex + delimiterLength(encoding);
    int textEndIndex = indexOfEos(data, textStartIndex, encoding);
    String text = new String(data, textStartIndex, textEndIndex - textStartIndex, charset);

    return new CommentFrame(language, description, text);
  }

  private static BinaryFrame decodeBinaryFrame(ParsableByteArray id3Data, int frameSize,
      String id) {
    byte[] frame = new byte[frameSize];
    id3Data.readBytes(frame, 0, frameSize);

    return new BinaryFrame(id, frame);
  }

  /**
   * Performs in-place removal of unsynchronization for {@code length} bytes starting from
   * {@link ParsableByteArray#getPosition()}
   *
   * @param data Contains the data to be processed.
   * @param length The length of the data to be processed.
   * @return The length of the data after processing.
   */
  private static int removeUnsynchronization(ParsableByteArray data, int length) {
    byte[] bytes = data.data;
    for (int i = data.getPosition(); i + 1 < length; i++) {
      if ((bytes[i] & 0xFF) == 0xFF && bytes[i + 1] == 0x00) {
        System.arraycopy(bytes, i + 2, bytes, i + 1, length - i - 2);
        length--;
      }
    }
    return length;
  }

  /**
   * Maps encoding byte from ID3v2 frame to a Charset.
   * @param encodingByte The value of encoding byte from ID3v2 frame.
   * @return Charset name.
   */
  private static String getCharsetName(int encodingByte) {
    switch (encodingByte) {
      case ID3_TEXT_ENCODING_ISO_8859_1:
        return "ISO-8859-1";
      case ID3_TEXT_ENCODING_UTF_16:
        return "UTF-16";
      case ID3_TEXT_ENCODING_UTF_16BE:
        return "UTF-16BE";
      case ID3_TEXT_ENCODING_UTF_8:
        return "UTF-8";
      default:
        return "ISO-8859-1";
    }
  }

  private static int indexOfEos(byte[] data, int fromIndex, int encoding) {
    int terminationPos = indexOfZeroByte(data, fromIndex);

    // For single byte encoding charsets, we're done.
    if (encoding == ID3_TEXT_ENCODING_ISO_8859_1 || encoding == ID3_TEXT_ENCODING_UTF_8) {
      return terminationPos;
    }

    // Otherwise ensure an even index and look for a second zero byte.
    while (terminationPos < data.length - 1) {
      if (terminationPos % 2 == 0 && data[terminationPos + 1] == (byte) 0) {
        return terminationPos;
      }
      terminationPos = indexOfZeroByte(data, terminationPos + 1);
    }

    return data.length;
  }

  private static int indexOfZeroByte(byte[] data, int fromIndex) {
    for (int i = fromIndex; i < data.length; i++) {
      if (data[i] == (byte) 0) {
        return i;
      }
    }
    return data.length;
  }

  private static int delimiterLength(int encodingByte) {
    return (encodingByte == ID3_TEXT_ENCODING_ISO_8859_1 || encodingByte == ID3_TEXT_ENCODING_UTF_8)
        ? 1 : 2;
  }

  public static String decodeGenre(int code) {
    return (0 < code && code <= standardGenres.length) ? standardGenres[code - 1] : null;
  }

  private static final class Id3Header {

    private final int majorVersion;
    private final boolean isUnsynchronized;
    private final int framesSize;

    public Id3Header(int majorVersion, boolean isUnsynchronized, int framesSize) {
      this.majorVersion = majorVersion;
      this.isUnsynchronized = isUnsynchronized;
      this.framesSize = framesSize;
    }

  }

  private static final String[] standardGenres = new String[] {
      // These are the official ID3v1 genres.
      "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge",
      "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap",
      "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska",
      "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient",
      "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical",
      "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise",
      "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
      "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic", "Darkwave",
      "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance", "Dream",
      "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap",
      "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave",
      "Psychadelic", "Rave", "Showtunes", "Trailer", "Lo-Fi", "Tribal",
      "Acid Punk", "Acid Jazz", "Polka", "Retro", "Musical", "Rock & Roll",
      "Hard Rock",
      // These were made up by the authors of Winamp but backported into the ID3 spec.
      "Folk", "Folk-Rock", "National Folk", "Swing", "Fast Fusion",
      "Bebob", "Latin", "Revival", "Celtic", "Bluegrass", "Avantgarde",
      "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock",
      "Slow Rock", "Big Band", "Chorus", "Easy Listening", "Acoustic", "Humour",
      "Speech", "Chanson", "Opera", "Chamber Music", "Sonata", "Symphony",
      "Booty Bass", "Primus", "Porn Groove", "Satire", "Slow Jam", "Club",
      "Tango", "Samba", "Folklore", "Ballad", "Power Ballad", "Rhythmic Soul",
      "Freestyle", "Duet", "Punk Rock", "Drum Solo", "A capella", "Euro-House",
      "Dance Hall",
      // These were also invented by the Winamp folks but ignored by the ID3 authors.
      "Goa", "Drum & Bass", "Club-House", "Hardcore", "Terror", "Indie",
      "BritPop", "Negerpunk", "Polsk Punk", "Beat", "Christian Gangsta Rap",
      "Heavy Metal", "Black Metal", "Crossover", "Contemporary Christian",
      "Christian Rock", "Merengue", "Salsa", "Thrash Metal", "Anime", "Jpop",
      "Synthpop"
  };

}
