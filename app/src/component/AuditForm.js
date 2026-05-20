import "../style/form.css";
import {useEffect, useMemo, useState} from "react";
import api from "../axiosConfig";
import AnswerTemplate from "./AnswerTemplate";
import LoadComponent from "./LoadComponent";

function AuditForm({children, object, open, close}) {
    const [criteria, setCriteria] = useState([]);
    const [scores, setScores] = useState([]);
    const [activeCriteria, setActiveCriteria] = useState(null);
    const [isLoading, setIsLoading] = useState(false);
    const [isLoaded, setIsLoaded] = useState(false);
    const [isSaved, setIsSaved] = useState(false);
    const [error, setError] = useState(false);
    const [failed, setFailed] = useState(false);

    const [score, setScore] = useState("");
    const [thought, setThought] = useState("");
    const [ibmScore, setIbmScore] = useState("");

    const [answers, setAnswers] = useState([]);

    const emptyAnswer = useMemo(() => ({
        title: "",
        description: "",
        recommendation: "",
        comment: "",
    }), []);

    const handleAnswerChange = (index, property, value) => {
        setAnswers(prev =>
            prev.map((a, i) =>
                i === index ? { ...a, [property]: value } : a
            )
        );
    };

    useEffect(() => {
        if (!activeCriteria) return;
        setScore(null);
        setAnswers([]);
        setError(false)
        setIsSaved(false)
    }, [activeCriteria]);

    useEffect(() => {
        const fetchData = async () => {
            try {
                const res = await api.get(`/guidelines/${children.id}`);
                const items = res.data.successCriteria;
                setCriteria(items);
                setActiveCriteria(items[0]);
                setIsLoaded(true);
            } catch (err) {
                console.error(err);
            }
        };

        if (children?.id) {
            fetchData();
        }
    }, [children.id]);

    useEffect(() => {
        if (!activeCriteria || !object) return;

        const fetchData = async () => {
            try {
                const res = await api.get(
                    `/audit/getAnswer?refId=${activeCriteria.refId}&id=${object}`
                );

                const items = res.data;

                setScore(items.score ?? "");
                setAnswers(
                    items.answers?.length > 0
                        ? items.answers
                        : [emptyAnswer]
                );

                setIsLoaded(true);
            } catch (err) {
                setScore("");
                setAnswers([emptyAnswer]);
                setIsSaved(false);
            }
        };

        fetchData();
    }, [activeCriteria, object, emptyAnswer]);


    useEffect(() => {
        const fetchData = async () => {
            try {
                const res = await api.get(`/audit/scores`);
                setScores(res.data)
            } catch (err) {
                console.error(err);
            }
        }
        fetchData();
    }, [])

    function selectNextCriteria() {
        const index = criteria.findIndex(c => c.refId === activeCriteria.refId);

        if (index !== -1 && index < criteria.length - 1) {
            setActiveCriteria(criteria[index + 1]);
            setThought("");
            setIbmScore("");
        }
    }

    function selectPreviousCriteria() {
        const index = criteria.findIndex(c => c.refId === activeCriteria.refId);

        if (index > 0 && index < criteria.length - 1) {
            setActiveCriteria(criteria[index - 1]);
            setThought("");
            setIbmScore("");
        } else {
            close();
        }
    }

    async function generateWithImage(e) {
        setFailed(false);
        const formData = new FormData();

        for (let i = 0; i < e.target.files.length; i++) {
            formData.append("image", e.target.files[i]);
        }

        setIsLoading(true);
        try {
            const res = await api.post(
                `/criteria/ai_picture?criteriaId=${activeCriteria.refId}&auditId=${object}`,
                formData,
                {
                    headers: {
                        "Content-Type": "multipart/form-data"
                    },
                }
            );
            setScore(res.data.overall_violation);
            setAnswers(dataMapper(res.data.violated_elements_and_reasons));
            setThought(res.data.thought_process);
            setIbmScore(res.data.ibm);
        } catch (err) {
            if (err.status === 500){
                setFailed(true);
            }
        }
        setIsLoading(false);
    }

    async function generateAnswer() {
        setFailed(false);
        setIsLoading(true);
        try {
            await api.get(`/criteria/ai_put?criteriaId=${activeCriteria.refId}&auditId=${object}`)
                .then(res => {
                    setScore(res.data.overall_violation);
                    setAnswers(dataMapper(res.data.violated_elements_and_reasons));
                    setThought(res.data.thought_process);
                    setIbmScore(res.data.ibm);
                });
        } catch (err) {
            if (err.status === 500){
                setFailed(true);
            }
        }
        setIsLoading(false);
    }

    function dataMapper(raw = []) {
        return raw.map(a => ({
            title: a.title ?? "",
            description: a.description ?? "",
            recommendation: a.recommendation ?? "",
            comment: "",
        }))
    }

    async function saveAnswer() {
        const id = object;
        const refId = activeCriteria.refId

        if (score === ""){
            setError(true);
            return
        } else {
            setError(false);
        }

        const formData = {
            id,
            refId,
            score,
            answers
        }

        setIsLoading(true);
        try {
            const res = await api.put(
                `/audit/saveAnswers`,
                formData
            );
            if (res.status === 200){
                setIsSaved(true);
            }
        } catch (err) {
            if (err.status === 400){
                setError(true);
            }
        }
        setIsLoading(false);
    }

    function addAnswer() {
        setAnswers(prev => [...prev, {...emptyAnswer}]);
    }

    function removeAnswer(index) {
        setAnswers(prev => prev.filter((_, i) => i !== index));
    }

    return (
        <div>
            {!isLoaded && (
                <div className="loading-container">
                    Loading...
                </div>
            )}
            {isLoading && (
                <LoadComponent></LoadComponent>
            )}
            {isLoaded && (
                <div>
                    <div className="header-title">
                        <div className="header-space">
                            <div className="text-space">
                                <div>
                                    <p>Audit</p>
                                    <i>{children.refId} {children.title}</i>
                                </div>
                                <div className="button-group">
                                    <button className="button-form"
                                            onClick={selectPreviousCriteria}>
                                        <i className="bi bi-arrow-bar-left"></i>
                                        Back
                                    </button>
                                    <button style={{"background-color": "#106DAA"}}
                                            className="button-form"
                                            onClick={() => saveAnswer()}>Save
                                        <i className="bi bi-floppy-fill"></i></button>
                                    <button className="button-form"
                                            onClick={selectNextCriteria}>
                                        Next
                                        <i className="bi bi-arrow-bar-right"></i></button>

                                </div>
                            </div>

                        </div>
                        <hr className="solid"/>
                    </div>

                    <div>
                        <form>
                            {activeCriteria && (
                                <div>
                                    <a href={activeCriteria.url} target="_blank"
                                       rel="noopener noreferrer"
                                       className="form-title">
                                        {activeCriteria.refId} {activeCriteria.title}
                                    </a>
                                    <p>{activeCriteria.description}</p>
                                </div>
                            )}
                            <i hidden={!isSaved} className="alert-good">Successfully saved!</i>
                            <i hidden={!error} className="alert-bad">Please fill in the required fields!</i>
                            <i hidden={!failed} className="alert-bad">Could not generate, please try again later</i>
                            <div className="form-answer">
                                <i className="form-answer-title required">Passed or failed?</i>
                                <div className="form-header">
                                    <select
                                        value={score}
                                        onChange={(e) => setScore(e.target.value)}
                                    >
                                        <option value="">Choose:</option>

                                        {scores.map((s) => (
                                            <option key={s} value={s}>
                                                {s}
                                            </option>
                                        ))}
                                    </select>
                                    {activeCriteria.fetchType === "text" && (
                                        <div>
                                            <button type="button"
                                                    className="button-ai"
                                                    onClick={generateAnswer}>Generate with AI
                                                <i className="bi bi-stars"></i></button>
                                            <span
                                                title="Please note that the answers generated by AI are not 100% correct and you, as the auditor, should always double check the website yourself">
                                                                                <i className="bi bi-info-circle"></i>
                                                                            </span>
                                        </div>
                                    )}
                                    {activeCriteria.fetchType === "image" && (
                                        <div>
                                            <label htmlFor="ai-image-upload" className="button-ai">
                                                Upload one or more picture to generate with AI
                                                <i className="bi bi-stars"></i>
                                            </label>
                                            <input type="file" id="ai-image-upload" multiple onChange={generateWithImage}
                                                   accept="image/*"/>
                                            <span
                                                title="Please note that the answers generated by AI are not 100% correct and you, as the auditor, should always double check the website yourself">
                                                                                <i className="bi bi-info-circle"></i>
                                                                            </span>
                                        </div>
                                    )}
                                </div>
                                {thought && (
                                    <div className="ai-box">
                                        <div className="box-item">
                                            <div className="box-header">
                                                Thought process
                                            </div>
                                            <div>
                                                {thought}
                                            </div>
                                        </div>
                                        <div className="box-item">
                                            <div className="box-header">
                                                These are the issues flagged by the IBM Accessibility Checker
                                            </div>
                                            <div>
                                                {!ibmScore && (
                                                    <div>
                                                        No issues found
                                                    </div>
                                                )}
                                                {ibmScore}
                                            </div>
                                        </div>
                                    </div>
                                )}

                                {answers.map((a, i) => (
                                    <div className="styling-form">
                                        <AnswerTemplate
                                            key={i}
                                            answerIndex={i}
                                            title={a.title}
                                            description={a.description}
                                            recommendation={a.recommendation}
                                            comment={a.comment}
                                            handleAnswerChange={handleAnswerChange}
                                        />
                                        <div>
                                            <button
                                                type="button"
                                                className="delete-button"
                                                onClick={() => removeAnswer(i)}>
                                                <i className="bi bi-trash3-fill"></i>
                                            </button>
                                        </div>
                                    </div>
                                ))}
                                <button type="button"
                                        className="button-add"
                                        onClick={addAnswer}>New
                                    <i className="bi bi-plus"></i></button>
                            </div>

                        </form>
                    </div>
                </div>

            )}
        </div>
    );
}

export default AuditForm;