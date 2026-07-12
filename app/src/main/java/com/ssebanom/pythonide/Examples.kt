package com.ssebanom.pythonide

import android.content.Context
import java.io.File

/** Sample scripts written to the scripts folder on first launch. */
object Examples {

    fun installIfNeeded(context: Context, scriptsDir: File) {
        val prefs = context.getSharedPreferences("pyide", Context.MODE_PRIVATE)
        // Bumped to v2 so the JavaScript/Lua samples are added on upgrade.
        if (prefs.getBoolean("examplesInstalled_v2", false)) return
        for ((name, body) in SCRIPTS) {
            val f = File(scriptsDir, name)
            if (!f.exists()) f.writeText(body)
        }
        prefs.edit().putBoolean("examplesInstalled_v2", true).apply()
    }

    private val SCRIPTS = mapOf(
        "main.py" to """
            # Welcome to Python IDE!
            # Press the play button to run this script.

            name = input("What is your name? ")
            print(f"Hello, {name}!")

            for i in range(3):
                print("Counting:", i + 1)
        """.trimIndent() + "\n",

        "example_numpy.py" to """
            import numpy as np

            a = np.arange(12).reshape(3, 4)
            print("Matrix:")
            print(a)
            print("Column sums:", a.sum(axis=0))
            print("Mean:", a.mean())

            x = np.linspace(0, 2 * np.pi, 8)
            print("sin(x):", np.round(np.sin(x), 3))
        """.trimIndent() + "\n",

        "example_matplotlib.py" to """
            import numpy as np
            import matplotlib.pyplot as plt

            x = np.linspace(0, 4 * np.pi, 400)
            plt.plot(x, np.sin(x), label="sin(x)")
            plt.plot(x, np.cos(x), label="cos(x)")
            plt.title("Trigonometry on Android")
            plt.legend()
            plt.grid(True)
            plt.show()   # the plot pops up automatically
        """.trimIndent() + "\n",

        "example_pandas.py" to """
            import pandas as pd

            df = pd.DataFrame({
                "city": ["Kampala", "Nairobi", "Lagos", "Cairo"],
                "population_m": [3.7, 5.5, 15.4, 22.1],
            })
            print(df)
            print()
            print("Total population:", df["population_m"].sum(), "million")
            print("Largest city:", df.loc[df["population_m"].idxmax(), "city"])
        """.trimIndent() + "\n",

        "example_requests.py" to """
            import requests

            print("Fetching a joke from the internet...")
            r = requests.get("https://official-joke-api.appspot.com/random_joke",
                             timeout=10)
            joke = r.json()
            print()
            print(joke["setup"])
            print("...", joke["punchline"])
        """.trimIndent() + "\n",

        "example_javascript.js" to """
            // JavaScript runs on-device via the Rhino engine (ES6).
            const nums = [5, 3, 8, 1, 9, 2];
            nums.sort((a, b) => a - b);
            console.log("Sorted:", nums.join(", "));

            const squares = nums.map(n => n * n);
            console.log("Squares:", squares.join(", "));

            const total = nums.reduce((a, b) => a + b, 0);
            console.log("Sum:", total);

            for (let i = 1; i <= 3; i++) {
                console.log(`Line ${'$'}{i}`);
            }
        """.trimIndent() + "\n",

        "example_lua.lua" to """
            -- Lua runs on-device via the LuaJ engine.
            local function factorial(n)
                if n <= 1 then return 1 end
                return n * factorial(n - 1)
            end

            for i = 1, 6 do
                print(i .. "! = " .. factorial(i))
            end

            local t = {"apple", "banana", "cherry"}
            for index, fruit in ipairs(t) do
                print(index, fruit)
            end
        """.trimIndent() + "\n",

        "example_files.py" to """
            # Scripts can read and write files in their own folder.
            with open("notes.txt", "w") as f:
                f.write("Saved from Python on Android!\n")

            with open("notes.txt") as f:
                print("File contents:", f.read().strip())

            import os
            print("Working directory:", os.getcwd())
            print("Files here:", os.listdir("."))
        """.trimIndent() + "\n",
    )
}
