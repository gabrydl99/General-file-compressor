"""General File Compressor entry point.

    python main.py                         -> opens the app
    python main.py --cli --percent 30 a.mp4 -> compresses without the window (testing)
"""
import multiprocessing
import sys

if __name__ == "__main__":
    multiprocessing.freeze_support()
    if "--cli" in sys.argv:
        from general_file_compressor.cli import main
        args = [a for a in sys.argv[1:] if a != "--cli"]
        sys.exit(main(args))
    from general_file_compressor.app import run
    sys.exit(run())
