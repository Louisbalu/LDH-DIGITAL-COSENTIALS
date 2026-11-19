package de.meinname.scopebox

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryCheckScannerTest {

    @Test
    fun testLeak() {
        val code = """
            void process() {
                char *buf = (char *)malloc(64);
                // keine Freigabe
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        assertEquals(1, findings.size, "Should detect 1 memory leak")
        assertEquals(MemoryIssueType.LEAK, findings[0].type)
        assertEquals("buf", findings[0].variableName)
    }

    @Test
    fun testNoLeakWithFree() {
        val code = """
            void process() {
                char *buf = malloc(64);
                strcpy(buf, "hello");
                free(buf);
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        assertEquals(0, findings.size, "Should detect no leak when free() is called")
    }

    @Test
    fun testReturnPointerNoLeak() {
        val code = """
            char* create_buffer(int size) {
                char *ptr = malloc(size);
                return ptr;
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        assertEquals(0, findings.size, "Returned pointer should not be reported as a leak")
    }

    @Test
    fun testDoubleFree() {
        val code = """
            void cleanup() {
                char *data = malloc(32);
                free(data);
                free(data);
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        val doubleFrees = findings.filter { it.type == MemoryIssueType.DOUBLE_FREE }
        assertEquals(1, doubleFrees.size, "Should detect 1 double free")
        assertEquals("data", doubleFrees[0].variableName)
    }

    @Test
    fun testUseAfterFree() {
        val code = """
            void write_data() {
                char *msg = malloc(16);
                free(msg);
                *msg = 'A';
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        val uaf = findings.filter { it.type == MemoryIssueType.USE_AFTER_FREE }
        assertEquals(1, uaf.size, "Should detect 1 use after free")
        assertEquals("msg", uaf[0].variableName)
    }

    @Test
    fun testEarlyReturnErrorPathLeak() {
        val code = """
            int execute(int flag) {
                char *p = malloc(100);
                if (flag < 0) {
                    return -1;
                }
                free(p);
                return 0;
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        val leakOnReturn = findings.filter { it.type == MemoryIssueType.LEAK }
        assertTrue(leakOnReturn.isNotEmpty(), "Should detect leak on early return path")
        assertEquals("p", leakOnReturn[0].variableName)
    }

    @Test
    fun testCommentsAndStringsIgnored() {
        val code = """
            void notes_only() {
                // char *fake = malloc(100);
                /* free(fake); */
                const char *msg = "malloc(100) and free(x) inside string";
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        assertEquals(0, findings.size, "Should ignore allocations inside comments and string literals")
    }

    @Test
    fun testReallocPattern() {
        val code = """
            void resize_buffer() {
                char *buf = malloc(10);
                buf = realloc(buf, 50);
                free(buf);
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        assertEquals(0, findings.size, "Realloc reassignment followed by free() is valid")
    }

    @Test
    fun testNewAndDelete() {
        val code = """
            void cpp_memory() {
                int *singleObj = new int(10);
                delete singleObj;

                int *arrayObj = new int[20];
                delete[] arrayObj;

                int *leaked = new int(99);
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        val leaks = findings.filter { it.type == MemoryIssueType.LEAK }
        assertEquals(1, leaks.size, "Should only report the un-deleted object")
        assertEquals("leaked", leaks[0].variableName)
    }

    @Test
    fun testStructAssignmentEscapes() {
        val code = """
            void store_in_struct(Node *node) {
                char *name = strdup("Item");
                node->name = name;
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        assertEquals(0, findings.size, "Pointer stored in struct should not be flagged as leak")
    }

    @Test
    fun testCallocUsage() {
        val code = """
            void allocate_table() {
                int *table = calloc(10, sizeof(int));
                free(table);
            }
        """.trimIndent()
        val findings = MemoryCheckScanner.scan(null, code)
        assertEquals(0, findings.size, "Calloc followed by free should have 0 findings")
    }
}
