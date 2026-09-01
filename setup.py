from setuptools import find_packages, setup

MIN_PY_VERSION = "3.10"
PACKAGES = find_packages()
VERSION = "0.21.3"

setup(
    name="julilys",
    packages=PACKAGES,
    version=VERSION,
    description="A python library for communicating with Plejd devices via bluetooth",
    author="None",
    author_email="none@twone.no",
    license="MIT",
    url="https://github.com/bspippi1337/julilys",
    download_url=f"https://github.com/bspippi1337/julilys/archive/v{VERSION}.tar.gz",
    install_requires=["aiohttp", "bleak", "bleak_retry_connector", "pydantic"],
    keywords=["julilys", "bluetooth", "homeassistant"],
    python_requires=f">={MIN_PY_VERSION}",
)
