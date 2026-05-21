module.exports = {
    outputFormat: ["json"],
    outputFolder: "accessibility-reports",
    reportLevels: ["violation", "potentialviolation", "recommendation", "potentialrecommendation", "manual"],
    failLevels: ["violation"],
    puppeteerOptions: {
        args: ["--no-sandbox", "--disable-setuid-sandbox", "--disable-dev-shm-usage"]
    }
};