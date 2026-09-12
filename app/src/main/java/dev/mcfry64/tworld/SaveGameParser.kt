package dev.mcfry64.tworld

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class LevelProgress(
    val levelNumber: Int,
    val isSolved: Boolean,
    val bestTimeTicks: Int,
    val name: String = "",
    val author: String = "",
)

data class SetProgress(
    val setName: String,
    val totalLevels: Int,
    val solvedCount: Int,
    val totalScore: Long,
    val levels: List<LevelProgress>
)

object SaveGameParser {

    private const val TWS_SIG = 0x999B3335L
    private const val TAG = "SaveGameParser"

    fun getSetProgress(setName: String, dataDir: String, setsDir: String, saveDir: String): SetProgress {
        // Tile World saves progress using the filename passed to it (including extension if provided)
        var twsFile = File(saveDir, "$setName.tws")
        
        // Also check without extension just in case
        if (!twsFile.exists()) {
            val baseName = File(setName).nameWithoutExtension
            if (File(saveDir, "$baseName.tws").exists()) {
                twsFile = File(saveDir, "$baseName.tws")
            }
        }

        val solvedLevels = mutableMapOf<Int, Int>() 

        if (twsFile.exists()) {
            parseTws(twsFile, solvedLevels)
        }

        // Handle .dac files by looking inside for the actual levelset file
        var targetSetName = setName
        if (targetSetName.lowercase().endsWith(".dac") || targetSetName.lowercase().endsWith(".dat")) {
            targetSetName = File(targetSetName).nameWithoutExtension
        }

        val dacFile = File(dataDir, "$targetSetName.dac").takeIf { it.exists() }
            ?: File(setsDir, "$targetSetName.dac").takeIf { it.exists() }
            ?: File(setsDir, setName).takeIf { it.exists() && it.name.lowercase().endsWith(".dac") }

        if (dacFile != null) {
            try {
                dacFile.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("file")) {
                            val parts = trimmed.split("=", limit = 2)
                            if (parts.size == 2 && parts[0].trim() == "file") {
                                val linkedFile = parts[1].trim()
                                targetSetName = File(linkedFile).nameWithoutExtension
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                Log.e(TAG, "Error reading DAC file: ${dacFile.name}")
            }
        }

        // Try to find .ccx or .dat file in the data dir
        val setFile = File(dataDir, "$targetSetName.ccx").takeIf { it.exists() }
            ?: File(dataDir, "$targetSetName.dat").takeIf { it.exists() }
            ?: File(dataDir, targetSetName).takeIf { it.exists() }

        val levelNames = mutableListOf<String>()
        val levelAuthors = mutableListOf<String>()
        var totalLevels = 149

        if (setFile != null) {
            if (setFile.name.endsWith(".ccx")) {
                parseCcxInfo(setFile, levelNames, levelAuthors)
            } else if (setFile.name.endsWith(".dat")) {
                // Try to find a companion .ccx for names/authors
                val companionCcx = File(dataDir, "${setFile.nameWithoutExtension}.ccx")
                if (companionCcx.exists()) {
                    parseCcxInfo(companionCcx, levelNames, levelAuthors)
                } else {
                    // Parse names directly from .dat binary
                    parseDatLevelNames(setFile, levelNames)
                }
                totalLevels = parseDatLevelCount(setFile)
            }
            totalLevels = if (levelNames.isNotEmpty()) levelNames.size else totalLevels
        }

        val levels = mutableListOf<LevelProgress>()
        var solvedCount = 0
        var totalScore = 0L

        for (i in 1..totalLevels) {
            val bestTime = solvedLevels[i]
            val isSolved = bestTime != null
            if (isSolved) {
                solvedCount++
                totalScore += (i * 500).toLong()
            }
            val name = if (i in 1..levelNames.size) levelNames[i - 1] else "Level $i"
            var author = if (i in 1..levelAuthors.size) levelAuthors[i - 1] else ""
            
            // Hardcoded fallback for the original game
            if (targetSetName.uppercase() == "CHIPS" || setName.lowercase().contains("cc-ms")) {
                if (author.isEmpty()) author = "Chuck Sommerville"
            }
            
            levels.add(LevelProgress(i, isSolved, bestTime ?: 0, name, author))
        }

        Log.d(TAG, "Progress for $setName: found ${solvedLevels.size} solved levels, total $totalLevels")
        return SetProgress(setName, totalLevels, solvedCount, totalScore, levels)
    }


    private fun parseCcxInfo(file: File, names: MutableList<String>, authors: MutableList<String>) {
        try {
            val content = file.readText()
            // Match level tags and the comment immediately preceding them if it exists
            val levelRegex = Regex("(?:<!--\\s*(.*?)\\s*-->\\s*)?<level\\s+([^>]+)>", RegexOption.DOT_MATCHES_ALL)
            val nameAttrRegex = Regex("name=\"([^\"]*)\"")
            val authorRegex = Regex("author=\"([^\"]*)\"")
            
            levelRegex.findAll(content).forEach { match ->
                val commentContent = match.groupValues[1].trim()
                val levelAttrs = match.groupValues[2]
                
                val nameMatch = nameAttrRegex.find(levelAttrs)
                val authorMatch = authorRegex.find(levelAttrs)
                
                // Prioritize name attribute, then comment
                var name = nameMatch?.groupValues?.get(1) ?: commentContent
                // Strip leading numbers from comments like "001: The Swarm"
                name = name.replace(Regex("^\\d+[:\\s-]*"), "").trim()
                
                names.add(name)
                authors.add(authorMatch?.groupValues?.get(1) ?: "")
            }
        } catch (_: Exception) {
            Log.e(TAG, "Error parsing CCX: ${file.name}")
        }
    }

    private fun parseDatLevelCount(file: File): Int {
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 6) return 149
                raf.seek(0)
                val signature = raf.readShort().toInt() and 0xFFFF
                if (java.lang.Short.reverseBytes(signature.toShort()).toInt() and 0xFFFF != 0xAAAC) return 149
                
                raf.seek(4)
                val count = raf.readShort().toInt() and 0xFFFF
                return java.lang.Short.reverseBytes(count.toShort()).toInt() and 0xFFFF
            }
        } catch (_: Exception) {
            Log.e(TAG, "Error parsing DAT: ${file.name}")
        }
        return 149
    }

    private fun parseDatLevelNames(file: File, names: MutableList<String>) {
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 6) return
                raf.seek(0)
                val signature = raf.readShort().toInt() and 0xFFFF
                if (java.lang.Short.reverseBytes(signature.toShort()).toInt() and 0xFFFF != 0xAAAC) return
                
                raf.seek(4)
                val levelCountLE = raf.readShort().toInt() and 0xFFFF
                val levelCount = java.lang.Short.reverseBytes(levelCountLE.toShort()).toInt() and 0xFFFF
                
                raf.seek(6)
                for (i in 0 until levelCount) {
                    val startPos = raf.filePointer
                    val recordLenLE = raf.readShort().toInt() and 0xFFFF
                    val recordLen = java.lang.Short.reverseBytes(recordLenLE.toShort()).toInt() and 0xFFFF
                    
                    // Skip level header (8 bytes)
                    raf.seek(startPos + 2 + 8)
                    
                    // Skip Layer 1
                    val s1LE = raf.readShort().toInt() and 0xFFFF
                    val s1 = java.lang.Short.reverseBytes(s1LE.toShort()).toInt() and 0xFFFF
                    raf.skipBytes(s1)
                    
                    // Skip Layer 2
                    val s2LE = raf.readShort().toInt() and 0xFFFF
                    val s2 = java.lang.Short.reverseBytes(s2LE.toShort()).toInt() and 0xFFFF
                    raf.skipBytes(s2)
                    
                    // Optional Fields
                    val ofLenLE = raf.readShort().toInt() and 0xFFFF
                    val ofLen = java.lang.Short.reverseBytes(ofLenLE.toShort()).toInt() and 0xFFFF
                    
                    var levelName = ""
                    val ofStart = raf.filePointer
                    while (raf.filePointer < ofStart + ofLen) {
                        val fieldType = raf.readByte().toInt() and 0xFF
                        val fieldLen = raf.readByte().toInt() and 0xFF
                        if (fieldType == 3 || fieldType == 9) {
                            val nameBytes = ByteArray(fieldLen)
                            raf.readFully(nameBytes)
                            levelName = String(nameBytes).replace("\u0000", "").trim()
                            break 
                        } else {
                            raf.skipBytes(fieldLen)
                        }
                    }
                    names.add(levelName)
                    
                    // Seek to start of next record
                    raf.seek(startPos + 2 + recordLen)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing DAT names: ${file.name}", e)
        }
    }

    private fun parseTws(file: File, solvedLevels: MutableMap<Int, Int>) {
        Log.d(TAG, "Parsing TWS file: ${file.absolutePath}, size: ${file.length()}")
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 8) return
                val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                raf.read(header.array())
                if (header.getInt(0).toLong() and 0xFFFFFFFFL != TWS_SIG) {
                    Log.w(TAG, "TWS signature mismatch for ${file.name}")
                    return
                }
                
                val headerExtLen = header[7].toInt() and 0xFF
                raf.seek(8L + headerExtLen)

                while (raf.filePointer + 4 <= raf.length()) {
                    val currentPos = raf.filePointer
                    val nextOffset = raf.readInt().let { Integer.reverseBytes(it) } // Little Endian read
                    if (nextOffset == 0) break
                    
                    // Sanity check for offset to prevent infinite loop or huge seeks
                    if (nextOffset < 0 || nextOffset > 1_000_000) {
                        Log.w(TAG, "Invalid TWS nextOffset: $nextOffset at $currentPos")
                        break
                    }

                    if (nextOffset >= 16 && raf.filePointer + 16 <= raf.length()) {
                        val levelNum = raf.readShort().let { java.lang.Short.reverseBytes(it).toInt() and 0xFFFF }
                        raf.seek(currentPos + 4 + 12) // Skip to time field (at record offset 16)
                        val bestTime = raf.readInt().let { Integer.reverseBytes(it) }
                        if (bestTime > 0 && bestTime != 0x7FFFFFFF) {
                             solvedLevels[levelNum] = bestTime
                             Log.v(TAG, "Parsed solved level: $levelNum, time: $bestTime")
                        }
                    }
                    raf.seek(currentPos + 4 + nextOffset.toLong())
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "Error parsing TWS: ${file.name}")
        }
    }

    fun findLevelByPassword(setName: String, inputPassword: String, dataDir: String, setsDir: String): Int? {
        val cleanPassword = inputPassword.trim().uppercase()
        if (cleanPassword.isEmpty()) return null

        var targetSetName = setName
        if (targetSetName.lowercase().endsWith(".dac") || targetSetName.lowercase().endsWith(".dat")) {
            targetSetName = File(targetSetName).nameWithoutExtension
        }

        val dacFile = File(dataDir, "$targetSetName.dac").takeIf { it.exists() }
            ?: File(setsDir, "$targetSetName.dac").takeIf { it.exists() }
            ?: File(setsDir, setName).takeIf { it.exists() && it.name.lowercase().endsWith(".dac") }

        if (dacFile != null) {
            try {
                dacFile.useLines { lines ->
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("file")) {
                            val parts = trimmed.split("=", limit = 2)
                            if (parts.size == 2 && parts[0].trim() == "file") {
                                val linkedFile = parts[1].trim()
                                targetSetName = File(linkedFile).nameWithoutExtension
                                break
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        val setFile = File(dataDir, "$targetSetName.ccx").takeIf { it.exists() }
            ?: File(dataDir, "$targetSetName.dat").takeIf { it.exists() }
            ?: File(dataDir, targetSetName).takeIf { it.exists() }
            ?: File(setsDir, "$targetSetName.ccx").takeIf { it.exists() }
            ?: File(setsDir, "$targetSetName.dat").takeIf { it.exists() }

        if (setFile == null) return null

        if (setFile.name.lowercase().endsWith(".ccx")) {
            return findPasswordInCcx(setFile, cleanPassword)
        } else if (setFile.name.lowercase().endsWith(".dat")) {
            val companionCcx = File(dataDir, "${setFile.nameWithoutExtension}.ccx").takeIf { it.exists() }
                ?: File(setsDir, "${setFile.nameWithoutExtension}.ccx").takeIf { it.exists() }
            if (companionCcx != null) {
                val ccxResult = findPasswordInCcx(companionCcx, cleanPassword)
                if (ccxResult != null) return ccxResult
            }
            return findPasswordInDat(setFile, cleanPassword)
        }
        return null
    }

    private fun findPasswordInCcx(file: File, searchPassword: String): Int? {
        try {
            val content = file.readText()
            val levelRegex = Regex("<level\\s+([^>]+)>", RegexOption.IGNORE_CASE)
            val passwordRegex = Regex("password=\"([^\"]*)\"", RegexOption.IGNORE_CASE)

            var levelIndex = 1
            levelRegex.findAll(content).forEach { match ->
                val levelAttrs = match.groupValues[1]
                val pwdMatch = passwordRegex.find(levelAttrs)
                val pwd = pwdMatch?.groupValues?.get(1)?.trim()?.uppercase() ?: ""
                if (pwd == searchPassword) {
                    return levelIndex
                }
                levelIndex++
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding password in CCX: ${file.name}", e)
        }
        return null
    }

    private fun findPasswordInDat(file: File, searchPassword: String): Int? {
        try {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 6) return null
                raf.seek(0)
                val signature = raf.readShort().toInt() and 0xFFFF
                if (java.lang.Short.reverseBytes(signature.toShort()).toInt() and 0xFFFF != 0xAAAC) return null

                raf.seek(4)
                val levelCountLE = raf.readShort().toInt() and 0xFFFF
                val levelCount = java.lang.Short.reverseBytes(levelCountLE.toShort()).toInt() and 0xFFFF

                raf.seek(6)
                for (i in 0 until levelCount) {
                    val startPos = raf.filePointer
                    val recordLenLE = raf.readShort().toInt() and 0xFFFF
                    val recordLen = java.lang.Short.reverseBytes(recordLenLE.toShort()).toInt() and 0xFFFF

                    // Skip level header (8 bytes)
                    raf.seek(startPos + 2 + 8)

                    // Skip Layer 1
                    val s1LE = raf.readShort().toInt() and 0xFFFF
                    val s1 = java.lang.Short.reverseBytes(s1LE.toShort()).toInt() and 0xFFFF
                    raf.skipBytes(s1)

                    // Skip Layer 2
                    val s2LE = raf.readShort().toInt() and 0xFFFF
                    val s2 = java.lang.Short.reverseBytes(s2LE.toShort()).toInt() and 0xFFFF
                    raf.skipBytes(s2)

                    // Optional Fields
                    val ofLenLE = raf.readShort().toInt() and 0xFFFF
                    val ofLen = java.lang.Short.reverseBytes(ofLenLE.toShort()).toInt() and 0xFFFF

                    val ofStart = raf.filePointer
                    while (raf.filePointer < ofStart + ofLen) {
                        val fieldType = raf.readByte().toInt() and 0xFF
                        val fieldLen = raf.readByte().toInt() and 0xFF
                        if (fieldType == 6 && fieldLen >= 4) {
                            val pwdBytes = ByteArray(4)
                            raf.readFully(pwdBytes)
                            if (fieldLen > 4) raf.skipBytes(fieldLen - 4)

                            val decodedChars = CharArray(4) { idx ->
                                ((pwdBytes[idx].toInt() and 0xFF) xor 0x99).toChar()
                            }
                            val decodedPwd = String(decodedChars).trim().uppercase()
                            if (decodedPwd == searchPassword) {
                                return i + 1
                            }
                        } else {
                            raf.skipBytes(fieldLen)
                        }
                    }

                    raf.seek(startPos + 2 + recordLen)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error finding password in DAT: ${file.name}", e)
        }
        return null
    }
}
