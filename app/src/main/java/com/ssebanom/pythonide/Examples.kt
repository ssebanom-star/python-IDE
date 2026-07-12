package com.ssebanom.pythonide

import android.content.Context
import java.io.File

/** Sample scripts written to the scripts folder on first launch. */
object Examples {

    fun installIfNeeded(context: Context, scriptsDir: File) {
        val prefs = context.getSharedPreferences("pyide", Context.MODE_PRIVATE)
        if (prefs.getBoolean("examplesInstalled", false)) return
        for ((name, body) in SCRIPTS) {
            val f = File(scriptsDir, name)
            if (!f.exists()) f.writeText(body)
        }
        prefs.edit().putBoolean("examplesInstalled", true).apply()
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
